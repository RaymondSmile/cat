<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core"%>
<%@ page contentType="text/html; charset=utf-8"%>
	<div class="text-left"></div>
     <div style="float:left;">
		&nbsp;开始
		<input type="text" id="startTime" style="width:150px;"/>
		结束
		<input type="text" id="endTime" style="width:150px;"/></div>
	应用名
	<input type="text" name="domain" id="domain" value="${payload.domain}" style="height:auto" class="input-small">
	机器名
	<input type="text" name="hostname" id="hostname" value="${payload.hostname}" style="height:auto" class="input-small"> 
	<input class="btn btn-primary  btn-sm"  style="margin-bottom:4px;" value="查询" onclick="queryNew()" type="submit">
		
	类型
	<label class="btn btn-info btn-sm">
	<input type="checkbox" checked="checked"  style="margin-bottom:0px;" id="show_puppet" class="typeCheckbox"/>puppet 
	</label><label class="btn btn-info btn-sm">
	<input type="checkbox" checked="checked"  style="margin-bottom:0px;" id="show_workflow" class="typeCheckbox"/>workflow 
	</label><label class="btn btn-info btn-sm">
	<input type="checkbox" checked="checked"  style="margin-bottom:0px;" id="show_lazyman" class="typeCheckbox"/>lazyman 
	</label>
	<br>
	</div>

<script>
	function typeCheckStr(){
		var result = "";
		if(!document.getElementById("show_puppet").checked){
			result += "showPuppet=false&";
		}
		if(!document.getElementById("show_workflow").checked){
			result += "showWorkflow=false&";
		}
		if(!document.getElementById("show_lazyman").checked){
			result += "showLazyman=false&";
		}
		return result;
	}
	function queryNew(){
		var startTime=$("#startTime").val();
		var endTime=$("#endTime").val();
		var domain=$("#domain").val();
		var hostname=$("#hostname").val();
		window.location.href="?op=view&domain="+domain+"&startTime="+startTime+"&endTime="+endTime+"&hostname="+hostname;
	}
	function queryFullScreen(isFullScreen){
		var typeStatus = typeCheckStr();
		<c:if test="${payload.refresh}">
			window.location.href="?"+typeStatus+"domain=${payload.domain}&hostname=${payload.hostname}&fullScreen="+isFullScreen+"&refresh=${payload.refresh}&frequency=${payload.frequency}";
		</c:if>
		<c:if test="${!payload.refresh}">
			window.location.href="?"+typeStatus+"domain=${payload.domain}&hostname=${payload.hostname}&fullScreen="+isFullScreen+"&refresh=${payload.refresh}&frequency=${payload.frequency}&startTime=<fmt:formatDate value="${payload.startTime}" pattern="yyyy-MM-dd HH:mm"/>&endTime=<fmt:formatDate value="${payload.endTime}" pattern="yyyy-MM-dd HH:mm"/>";
		</c:if>
	}
	function queryFrequency(frequency){
		var typeStatus = typeCheckStr();
		window.location.href="?"+typeStatus+"domain=${payload.domain}&hostname=${payload.hostname}&fullScreen=${payload.fullScreen}&refresh=true&frequency="+frequency;
	}
</script>