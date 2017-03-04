package com.jadyer.seed.qss;

import com.jadyer.seed.comm.constant.CodeEnum;
import com.jadyer.seed.comm.constant.CommonResult;
import com.jadyer.seed.qss.module.ScheduleTask;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Date;

@Controller
@RequestMapping(value="/qss")
public class QssController {
	@Resource
	private QssService qssService;
	
	@RequestMapping(value="/list")
	public String list(HttpServletRequest request){
		request.setAttribute("taskList", qssService.getAllTask());
		return "quartzList";
	}


	@ResponseBody
	@RequestMapping(value="/add")
	public CommonResult add(ScheduleTask task, String dynamicPassword){
		if(!this.verifyDynamicPassword(dynamicPassword)){
			return new CommonResult(CodeEnum.SYSTEM_BUSY.getCode(), "动态密码不正确");
		}
		ScheduleTask obj = qssService.saveTask(task);
		return new CommonResult(CodeEnum.SUCCESS.getCode(), String.valueOf(obj.getId()));
	}


	@ResponseBody
	@RequestMapping(value="/delete/{id}/{dynamicPassword}")
	public CommonResult delete(@PathVariable int id, @PathVariable String dynamicPassword){
		if(!this.verifyDynamicPassword(dynamicPassword)){
			return new CommonResult(CodeEnum.SYSTEM_BUSY.getCode(), "动态密码不正确");
		}
		qssService.deleteTask(id);
		return new CommonResult();
	}


	@ResponseBody
	@RequestMapping(value="/updateStatus")
	public CommonResult updateStatus(int id, int status, String dynamicPassword){
		if(!this.verifyDynamicPassword(dynamicPassword)){
			return new CommonResult(CodeEnum.SYSTEM_BUSY.getCode(), "动态密码不正确");
		}
		if(qssService.updateStatus(id, status)){
			return new CommonResult();
		}else{
			return new CommonResult(CodeEnum.SYSTEM_ERROR);
		}
	}


	@ResponseBody
	@RequestMapping(value="/updateCron")
	public CommonResult updateCron(int id, String cron, String dynamicPassword){
		if(!this.verifyDynamicPassword(dynamicPassword)){
			return new CommonResult(CodeEnum.SYSTEM_BUSY.getCode(), "动态密码不正确");
		}
		if(qssService.updateCron(id, cron)){
			return new CommonResult();
		}else{
			return new CommonResult(CodeEnum.SYSTEM_ERROR);
		}
	}


	/**
	 * 立即执行一个QuartzJOB
	 */
	@ResponseBody
	@RequestMapping(value="/triggerJob/{id}/{dynamicPassword}")
	public CommonResult triggerJob(@PathVariable int id, @PathVariable String dynamicPassword){
		if(!this.verifyDynamicPassword(dynamicPassword)){
			return new CommonResult(CodeEnum.SYSTEM_BUSY.getCode(), "动态密码不正确");
		}
		ScheduleTask task = qssService.getTaskById(id);
		qssService.triggerJob(task);
		return new CommonResult();
	}


	/**
	 * 验证动态密码是否正确
	 * <p>
	 *     每个动态密码有效期为10分钟
	 * </p>
	 * @return 动态密码正确则返回true，反之false
	 */
	private boolean verifyDynamicPassword(String dynamicPassword){
		String timeFlag = DateFormatUtils.format(new Date(), "HHmm").substring(0, 3) + "0";
		String generatePassword = DigestUtils.md5Hex(timeFlag + "https://jadyer.github.io/" + timeFlag);
		return StringUtils.isNotBlank(dynamicPassword) && generatePassword.equalsIgnoreCase(dynamicPassword);
	}
}