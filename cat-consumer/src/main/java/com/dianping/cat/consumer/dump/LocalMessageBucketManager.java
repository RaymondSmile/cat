package com.dianping.cat.consumer.dump;

import io.netty.buffer.ByteBuf;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.unidal.helper.Scanners;
import org.unidal.helper.Scanners.FileMatcher;
import org.unidal.helper.Threads;
import org.unidal.helper.Threads.Task;
import org.unidal.lookup.ContainerHolder;
import org.unidal.lookup.annotation.Inject;

import com.dianping.cat.Cat;
import com.dianping.cat.CatConstants;
import com.dianping.cat.configuration.NetworkInterfaceManager;
import com.dianping.cat.configuration.ServerConfigManager;
import com.dianping.cat.hadoop.hdfs.LogviewUploader;
import com.dianping.cat.message.Event;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.MessageProducer;
import com.dianping.cat.message.Transaction;
import com.dianping.cat.message.internal.MessageId;
import com.dianping.cat.message.spi.MessageTree;
import com.dianping.cat.message.spi.core.MessagePathBuilder;
import com.dianping.cat.message.spi.internal.DefaultMessageTree;
import com.dianping.cat.statistic.ServerStatisticManager;
import com.dianping.cat.storage.message.LocalMessageBucket;
import com.dianping.cat.storage.message.MessageBlock;
import com.dianping.cat.storage.message.MessageBucket;
import com.dianping.cat.storage.message.MessageBucketManager;

public class LocalMessageBucketManager extends ContainerHolder implements MessageBucketManager, Initializable,
      LogEnabled {
	public static final String ID = "local";

	private static final long ONE_HOUR = 60 * 60 * 1000L;

	private File m_baseDir;

	private ConcurrentHashMap<String, LocalMessageBucket> m_buckets = new ConcurrentHashMap<String, LocalMessageBucket>();

	@Inject
	private ServerConfigManager m_configManager;

	@Inject
	private ServerStatisticManager m_serverStateManager;

	@Inject
	private MessagePathBuilder m_pathBuilder;

	@Inject
	private LogviewUploader m_logviewUploader;

	private String m_localIp = NetworkInterfaceManager.INSTANCE.getLocalHostAddress();

	private long m_error;

	private long m_total;

	private Logger m_logger;

	private int m_gzipThreads = 20;

	private int m_gzipMessageSize = 10000;

	private int m_messageBlockSize = 10000;

	private BlockingQueue<MessageBlock> m_messageBlocks = new LinkedBlockingQueue<MessageBlock>(m_messageBlockSize);

	private ConcurrentHashMap<Integer, LinkedBlockingQueue<MessageItem>> m_messageQueues = new ConcurrentHashMap<Integer, LinkedBlockingQueue<MessageItem>>();

	public void archive(long startTime) {
		String path = m_pathBuilder.getPath(new Date(startTime), "");
		List<String> keys = new ArrayList<String>();

		for (String key : m_buckets.keySet()) {
			if (key.startsWith(path)) {
				keys.add(key);
			}
		}
		try {
			for (String key : keys) {
				LocalMessageBucket bucket = m_buckets.get(key);

				try {
					MessageBlock block = bucket.flushBlock();

					if (block != null) {
						m_messageBlocks.put(block);
					}
				} catch (IOException e) {
					Cat.logError(e);
				}
			}
		} catch (Exception e) {
			Cat.logError(e);
		}
	}

	@Override
	public void enableLogging(Logger logger) {
		m_logger = logger;
	}

	@Override
	public void initialize() throws InitializationException {
		if (m_baseDir == null) {
			m_baseDir = new File(m_configManager.getHdfsLocalBaseDir(ServerConfigManager.DUMP_DIR));
		}

		Threads.forGroup("cat").start(new BlockDumper());
		Threads.forGroup("cat").start(new OldMessageMover());

		if (m_configManager.isLocalMode()) {
			m_gzipThreads = 1;
		}

		for (int i = 0; i < m_gzipThreads; i++) {
			LinkedBlockingQueue<MessageItem> messageQueue = new LinkedBlockingQueue<MessageItem>(m_gzipMessageSize);

			m_messageQueues.put(i, messageQueue);
			Threads.forGroup("cat").start(new MessageGzip(messageQueue, i));
		}
	}

	@Override
	public MessageTree loadMessage(String messageId) throws IOException {
		MessageProducer cat = Cat.getProducer();
		Transaction t = cat.newTransaction("BucketService", getClass().getSimpleName());

		t.setStatus(Message.SUCCESS);

		try {
			MessageId id = MessageId.parse(messageId);
			final String path = m_pathBuilder.getPath(new Date(id.getTimestamp()), "");
			final File dir = new File(m_baseDir, path);
			final String key = id.getDomain() + '-' + id.getIpAddress();
			final List<String> paths = new ArrayList<String>();

			Scanners.forDir().scan(dir, new FileMatcher() {
				@Override
				public Direction matches(File base, String name) {
					if (name.contains(key) && !name.endsWith(".idx")) {
						paths.add(path + name);
					}
					return Direction.NEXT;
				}
			});

			for (String dataFile : paths) {
				LocalMessageBucket bucket = m_buckets.get(dataFile);

				if (bucket != null) {
					MessageBlock block = bucket.flushBlock();

					if (block != null) {
						boolean first = m_messageBlocks.offer(block);

						LockSupport.parkNanos(200 * 1000 * 1000L); // wait 200 ms

						if (first == false) {
							boolean retry = m_messageBlocks.offer(block);

							if (retry == false) {
								Cat.logError(new RuntimeException("error flush block when read logview"));
							} else {
								LockSupport.parkNanos(200 * 1000 * 1000L); // wait 200 ms
							}
						}
					}
					MessageTree tree = bucket.findByIndex(id.getIndex());

					if (tree != null && tree.getMessageId().equals(messageId)) {
						t.addData("path", dataFile);
						return tree;
					}
				} else {
					File file = new File(m_baseDir, dataFile);

					if (file.exists()) {
						try {
							bucket = (LocalMessageBucket) lookup(MessageBucket.class, LocalMessageBucket.ID);
							bucket.setBaseDir(m_baseDir);
							bucket.initialize(dataFile);

							MessageTree tree = bucket.findByIndex(id.getIndex());

							if (tree != null && tree.getMessageId().equals(messageId)) {
								t.addData("path", dataFile);
								return tree;
							}
						} catch (Exception e) {
							Cat.logError(e);
						} finally {
							bucket.close();
							release(bucket);
						}
					}
				}
			}

			return null;
		} catch (IOException e) {
			t.setStatus(e);
			cat.logError(e);
			throw e;
		} catch (RuntimeException e) {
			t.setStatus(e);
			cat.logError(e);
			throw e;
		} catch (Error e) {
			t.setStatus(e);
			cat.logError(e);
			throw e;
		} finally {
			t.complete();
		}
	}

	private void logStorageState(final MessageTree tree) {
		int size = ((DefaultMessageTree) tree).getBuffer().readableBytes();
		String domain = tree.getDomain();

		m_serverStateManager.addMessageSize(domain, size);
		if (m_total % (CatConstants.SUCCESS_COUNT) == 0) {
			m_serverStateManager.addMessageDump(CatConstants.SUCCESS_COUNT);

			Message message = tree.getMessage();

			if (message instanceof Transaction) {
				long delay = System.currentTimeMillis() - tree.getMessage().getTimestamp()
				      - ((Transaction) message).getDurationInMillis();

				m_serverStateManager.addProcessDelay(delay);
			}
		}
	}

	private void moveFile(String path) {
		File file = new File(m_baseDir, path);
		boolean success = m_logviewUploader.uploadLogviewFile(path, file);

		if (success) {
			File parent = file.getParentFile();

			file.delete();
			parent.delete(); // delete it if empty
			parent.getParentFile().delete(); // delete it if empty
		}
	}

	private void moveOldMessages() {
		final List<String> paths = new ArrayList<String>();

		Scanners.forDir().scan(m_baseDir, new FileMatcher() {
			@Override
			public Direction matches(File base, String path) {
				if (new File(base, path).isFile()) {
					if (path.indexOf(".idx") == -1 && shouldUpload(path)) {
						paths.add(path);
					}
				}
				return Direction.DOWN;
			}
		});

		if (paths.size() > 0) {
			String ip = NetworkInterfaceManager.INSTANCE.getLocalHostAddress();
			Transaction t = Cat.newTransaction("System", "Move" + "-" + ip);

			t.setStatus(Message.SUCCESS);

			for (String path : paths) {
				File file = new File(m_baseDir, path);
				String loginfo = "path:" + m_baseDir + "/" + path + ",file size: " + file.length();
				LocalMessageBucket bucket = m_buckets.get(path);

				if (bucket != null) {
					try {
						bucket.close();
						Cat.getProducer().logEvent("Upload", "Outbox.Normal", Message.SUCCESS, loginfo);
					} catch (Exception e) {
						t.setStatus(e);
						Cat.logError(e);
						m_logger.error(e.getMessage(), e);
					} finally {
						m_buckets.remove(path);
						release(bucket);
					}
				}
				try {
					moveFile(path);
					moveFile(path + ".idx");

					Cat.getProducer().logEvent("Upload", "Outbox.Abnormal", Message.SUCCESS, loginfo);
				} catch (Exception e) {
					t.setStatus(e);
					Cat.logError(e);
					m_logger.error(e.getMessage(), e);
				}
			}
			t.complete();
		}
	}

	public void setBaseDir(File baseDir) {
		m_baseDir = baseDir;
	}

	public void setLocalIp(String localIp) {
		m_localIp = localIp;
	}

	private boolean shouldUpload(String path) {
		if (path.indexOf("draft") > -1 || path.indexOf("outbox") > -1) {
			return false;
		}
		long current = System.currentTimeMillis();
		long currentHour = current - current % ONE_HOUR;
		long lastHour = currentHour - ONE_HOUR;
		long nextHour = currentHour + ONE_HOUR;
		SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd/HH");
		String currentHourStr = sdf.format(new Date(currentHour));
		String lastHourStr = sdf.format(new Date(lastHour));
		String nextHourStr = sdf.format(new Date(nextHour));

		int indexOf = path.indexOf(currentHourStr);
		int indexOfLast = path.indexOf(lastHourStr);
		int indexOfNext = path.indexOf(nextHourStr);

		if (indexOf > -1 || indexOfLast > -1 || indexOfNext > -1) {
			return false;
		}
		return true;
	}

	@Override
	public void storeMessage(final MessageTree tree, final MessageId id) throws IOException {
		m_total++;
		boolean errorFlag = true;
		int index = (int) (m_total % m_gzipThreads);
		MessageItem messageItem = new MessageItem(tree, id);
		int retryTime = 0;

		while (retryTime < m_gzipThreads) {
			LinkedBlockingQueue<MessageItem> queue = m_messageQueues.get((index + retryTime) % m_gzipThreads);
			boolean result = queue.offer(messageItem);

			if (result) {
				errorFlag = false;
				break;
			}
			retryTime++;
		}

		if (errorFlag) {
			m_error++;
			if (m_error % (CatConstants.ERROR_COUNT * 10) == 0) {
				m_logger.error("Error when offer message tree to gzip queue! overflow :" + m_error + ". Gzip thread :"
				      + index);
			}
			m_serverStateManager.addMessageDumpLoss(1);
		}
		logStorageState(tree);
	}

	private class BlockDumper implements Task {
		private int m_errors;

		@Override
		public String getName() {
			return "LocalMessageBucketManager-BlockDumper";
		}

		@Override
		public void run() {
			try {
				while (true) {
					MessageBlock block = m_messageBlocks.poll(5, TimeUnit.MILLISECONDS);

					if (block != null) {
						long time = System.currentTimeMillis();
						String dataFile = block.getDataFile();
						LocalMessageBucket bucket = m_buckets.get(dataFile);

						try {
							bucket.getWriter().writeBlock(block);
						} catch (Throwable e) {
							m_errors++;

							if (m_errors == 1 || m_errors % 100 == 0) {
								Cat.getProducer().logError(
								      new RuntimeException("Error when dumping for bucket: " + dataFile + ".", e));
							}
						}
						m_serverStateManager.addBlockTotal(1);
						long duration = System.currentTimeMillis() - time;
						m_serverStateManager.addBlockTime(duration);
					}
				}
			} catch (InterruptedException e) {
				// ignore it
			}
		}

		@Override
		public void shutdown() {
		}
	}

	private class MessageGzip implements Task {

		private int m_index;

		public BlockingQueue<MessageItem> m_messageQueue;

		private int m_count = -1;

		public MessageGzip(BlockingQueue<MessageItem> messageQueue, int index) {
			m_messageQueue = messageQueue;
			m_index = index;
		}

		@Override
		public String getName() {
			return "Message-Gzip-" + m_index;
		}

		private void gzipMessage(MessageItem item) {
			try {
				MessageId id = item.getMessageId();
				String name = id.getDomain() + '-' + id.getIpAddress() + '-' + m_localIp;
				String path = m_pathBuilder.getPath(new Date(id.getTimestamp()), name);
				LocalMessageBucket bucket = m_buckets.get(path);

				if (bucket == null) {
					synchronized (m_buckets) {
						bucket = m_buckets.get(path);
						if (bucket == null) {
							bucket = (LocalMessageBucket) lookup(MessageBucket.class, LocalMessageBucket.ID);
							bucket.setBaseDir(m_baseDir);
							bucket.initialize(path);

							LocalMessageBucket last = m_buckets.putIfAbsent(path, bucket);

							if (last != null) {
								bucket.close();

								Cat.logEvent("BucketConcurrentModify", path, Event.SUCCESS, null);
							}

							bucket = m_buckets.get(path);
						}
					}
				}

				DefaultMessageTree tree = (DefaultMessageTree) item.getTree();
				ByteBuf buf = tree.getBuffer();
				MessageBlock bolck = bucket.storeMessage(buf, id);

				if (bolck != null) {
					if (!m_messageBlocks.offer(bolck)) {
						m_serverStateManager.addBlockLoss(1);
						m_logger.error("Error when offer the block to the dump!");
					}
				}
			} catch (Throwable e) {
				Cat.logError(e);
			}
		}

		private void gzipMessageWithMonitor(MessageItem item) {
			Transaction t = Cat.newTransaction("Gzip", "Thread-" + m_index);
			t.setStatus(Transaction.SUCCESS);

			gzipMessage(item);
			t.complete();
		}

		@Override
		public void run() {
			try {
				while (true) {
					MessageItem item = m_messageQueue.poll(5, TimeUnit.MILLISECONDS);

					if (item != null) {
						m_count++;
						if (m_count % (10000) == 0) {
							gzipMessageWithMonitor(item);
						} else {
							gzipMessage(item);
						}
					}
				}
			} catch (InterruptedException e) {
				// ignore it
			}
		}

		@Override
		public void shutdown() {

		}
	}

	class MessageItem {
		private MessageTree m_tree;

		private MessageId m_messageId;

		public MessageItem(MessageTree tree, MessageId messageId) {
			m_tree = tree;
			m_messageId = messageId;
		}

		public MessageId getMessageId() {
			return m_messageId;
		}

		public MessageTree getTree() {
			return m_tree;
		}

		public void setMessageId(MessageId messageId) {
			m_messageId = messageId;
		}

		public void setTree(MessageTree tree) {
			m_tree = tree;
		}

	}

	class OldMessageMover implements Task {
		@Override
		public String getName() {
			return "LocalMessageBucketManager-OldMessageMover";
		}

		@Override
		public void run() {
			boolean active = true;

			while (active) {
				try {
					long current = System.currentTimeMillis() / 1000 / 60;
					int min = (int) (current % (60));

					// make system 0-10 min is not busy
					if (min > 10) {
						moveOldMessages();
					}
				} catch (Throwable e) {
					m_logger.error(e.getMessage(), e);
				}
				try {
					Thread.sleep(2 * 60 * 1000L);
				} catch (InterruptedException e) {
					active = false;
				}
			}
		}

		@Override
		public void shutdown() {
		}
	}

}