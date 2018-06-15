package com.jadyer.seed.qss;

import com.jadyer.seed.comm.annotation.SeedLock;
import com.jadyer.seed.comm.constant.CodeEnum;
import com.jadyer.seed.comm.constant.SeedConstants;
import com.jadyer.seed.comm.exception.SeedException;
import com.jadyer.seed.qss.helper.JobAllowConcurrentFactory;
import com.jadyer.seed.qss.helper.JobDisallowConcurrentFactory;
import com.jadyer.seed.qss.model.ScheduleTask;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class QssServiceHelper {
    /**
     * 注入Spring管理的Scheduler
     * ----------------------------------------------------------------------------------------------------
     * 1.原本应该注入applicationContext.xml配置的org.springframework.scheduling.quartz.SchedulerFactoryBean
     * 2.由于SchedulerFactoryBean是一个工厂Bean，得到的不是它本身，而是它负责创建的org.quartz.impl.StdScheduler
     *   所以就要注意：在使用注解注入SchedulerFactoryBean的时候，要通过类型来注入，否则会报告类似下面的异常
     *   Bean named 'schedulerFactoryBean' must be of type [org.springframework.scheduling.quartz.SchedulerFactoryBean], but was actually of type [org.quartz.impl.StdScheduler]
     * 3.在查看SchedulerFactoryBean源码后发现，它的getObject()方法是返回的Scheduler对象
     *   既然如此，我们就不必注入SchedulerFactoryBean再调用getScheduler()这么麻烦了，可以直接声明Scheduler对象
     *   这里涉及到getBean("bean")和getBean("&bean")的区别
     * ----------------------------------------------------------------------------------------------------
     * FactoryBean源代码分析
     * 如果bean实现了FactoryBean接口，那么BeanFactory将把它作为一个bean工厂，而不是直接作为普通bean
     * 正常情况下，BeanFactory的getBean("bean")返回FactoryBean生产的bean实例，也就是getObject()里面的东西
     * 如果要返回FactoryBean本身的实例，需调用getBean("&bean")
     * ----------------------------------------------------------------------------------------------------
     */
    @Resource
    private Scheduler scheduler;

    /**
     * 获取所有正在运行的QuartzJob
     * -------------------------------------------------------------------------------------
     * Trigger各状态说明
     * None------Trigger已经完成，且不会再执行，或者找不到该触发器，或者Trigger已被删除
     * NORMAL----正常状态
     * PAUSED----暂停状态
     * COMPLETE--触发器完成，但任务可能还正在执行中
     * BLOCKED---线程阻塞状态
     * ERROR-----出现错误
     * -------------------------------------------------------------------------------------
     */
    public List<ScheduleTask> getAllRunningJob(){
        try{
            List<JobExecutionContext> executingJobs = scheduler.getCurrentlyExecutingJobs();
            List<ScheduleTask> taskList = new ArrayList<>(executingJobs.size());
            for(JobExecutionContext obj : executingJobs){
                taskList.add(this.convertToScheduleTask(obj.getJobDetail().getKey(), obj.getTrigger()));
            }
            return taskList;
        }catch(SchedulerException e){
            throw new SeedException(CodeEnum.SYSTEM_ERROR.getCode(), "获取所有正在运行的QuartzJob失败", e);
        }
    }


    /**
     * 获取所有计划中的QuartzJob
     */
    public List<ScheduleTask> getAllJob(){
        try{
            List<ScheduleTask> taskList = new ArrayList<>();
            Set<JobKey> jobKeys = scheduler.getJobKeys(GroupMatcher.anyJobGroup());
            for(JobKey jobKey : jobKeys){
                List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
                for(Trigger trigger : triggers){
                    taskList.add(this.convertToScheduleTask(jobKey, trigger));
                }
            }
            return taskList;
        }catch(SchedulerException e){
            throw new SeedException(CodeEnum.SYSTEM_ERROR.getCode(), "获取所有计划中的QuartzJob失败", e);
        }
    }


    /**
     * 添加QuartzJob
     */
    @SeedLock(key="#task.jobname")
    public void addJob(ScheduleTask task){
        if(null==task || task.getStatus()==SeedConstants.QSS_STATUS_STOP){
            return;
        }
        TriggerKey triggerKey = TriggerKey.triggerKey(task.getJobname());
        try{
            CronTrigger trigger = (CronTrigger)scheduler.getTrigger(triggerKey);
            if(null!=trigger){
                this.updateJobCron(task.getJobname(), task.getCron());
            }else{
                //Trigger不存在就创建一个
                Class<? extends Job> clazz = SeedConstants.QSS_CONCURRENT_YES == task.getConcurrent() ? JobAllowConcurrentFactory.class : JobDisallowConcurrentFactory.class;
                JobDetail jobDetail = JobBuilder.newJob(clazz).withIdentity(task.getJobname()).build();
                jobDetail.getJobDataMap().put(SeedConstants.QSS_JOB_DATAMAP_KEY, task);
                //表达式调度构建器
                CronScheduleBuilder scheduleBuilder = CronScheduleBuilder.cronSchedule(task.getCron());
                //按新的cronExpression表达式构建一个新的Trigger
                trigger = TriggerBuilder.newTrigger().withIdentity(task.getJobname()).withSchedule(scheduleBuilder).build();
                scheduler.scheduleJob(jobDetail, trigger);
            }
        }catch(SchedulerException e){
            throw new SeedException(CodeEnum.SYSTEM_ERROR.getCode(), "添加QuartzJob失败："+ReflectionToStringBuilder.toString(task), e);
        }
    }


    /**
     * 更新QuartzJobCronExpression
     * @return <code>null</code> if a <code>Trigger</code> with the given
     *         name & group was not found and removed from the store (and the 
     *         new trigger is therefore not stored), otherwise
     *         the first fire time of the newly scheduled trigger is returned.
     */
    void updateJobCron(String jobname, String cron){
        TriggerKey triggerKey = TriggerKey.triggerKey(jobname);
        try{
            // TODO 测试下更新后会不会立即自动执行一次 https://ask.csdn.net/questions/203797
            // https://www.cnblogs.com/shihaiming/p/7814516.html
            // https://blog.csdn.net/xiyang_1990/article/details/72178030
            // https://segmentfault.com/a/1190000009128328
            CronTrigger trigger = (CronTrigger)scheduler.getTrigger(triggerKey);
            if(cron.equals(trigger.getCronExpression())){
                return;
            }
            CronScheduleBuilder scheduleBuilder = CronScheduleBuilder.cronSchedule(cron);
            //按新的cronExpression表达式重新构建Trigger
            trigger = trigger.getTriggerBuilder().withIdentity(triggerKey).withSchedule(scheduleBuilder).build();
            //按新的Trigger重新设置Job执行
            scheduler.rescheduleJob(triggerKey, trigger);
        }catch(SchedulerException e){
            throw new SeedException(CodeEnum.SYSTEM_ERROR.getCode(), "更新QuartzJobCronExpression失败：jobname=["+jobname+"]，cron=["+cron+"]", e);
        }
    }


    /**
     * 立即执行一个QuartzJob（只会运行一次）
     * ----------------------------------------------------------------------------
     * Quartz是通过临时生成一个Trigger（Trigger的key是随机生成的）的方式实现的
     * 该临时Trigger将在本次任务运行完成之后自动删除
     * ----------------------------------------------------------------------------
     */
    void triggerJob(String jobname){
        JobKey jobKey = JobKey.jobKey(jobname);
        try{
            scheduler.triggerJob(jobKey);
        }catch(SchedulerException e){
            throw new SeedException(CodeEnum.SYSTEM_ERROR.getCode(), "立即执行QuartzJob失败：jobname=["+jobname+"]", e);
        }
    }


    /**
     * 删除一个QuartzJob
     * 删除任务后，所对应的Trigger也将被删除
     * @return rue if the Job was found and deleted.
     */
    boolean deleteJob(String jobname){
        JobKey jobKey = JobKey.jobKey(jobname);
        try{
            return scheduler.deleteJob(jobKey);
        }catch(SchedulerException e){
            throw new SeedException(CodeEnum.SYSTEM_ERROR.getCode(), "删除QuartzJob失败：jobname=["+jobname+"]", e);
        }
    }


    /**
     * 暂停一个QuartzJob
     */
    void pauseJob(String jobname){
        JobKey jobKey = JobKey.jobKey(jobname);
        try{
            scheduler.pauseJob(jobKey);
        }catch(SchedulerException e){
            throw new SeedException(CodeEnum.SYSTEM_ERROR.getCode(), "暂停QuartzJob失败：jobname=["+jobname+"]", e);
        }
    }


    /**
     * 恢复一个QuartzJob
     */
    void resumeJob(String jobname){
        JobKey jobKey = JobKey.jobKey(jobname);
        try{
            scheduler.resumeJob(jobKey);
        }catch(SchedulerException e){
            throw new SeedException(CodeEnum.SYSTEM_ERROR.getCode(), "恢复QuartzJob失败：jobname=["+jobname+"]", e);
        }
    }


    private ScheduleTask convertToScheduleTask(JobKey jobKey, Trigger trigger) throws SchedulerException {
        ScheduleTask task = new ScheduleTask();
        String[] jobname = jobKey.getName().split(":");
        task.setId(Long.parseLong(jobname[0]));
        task.setAppname(jobname[1]);
        task.setName(jobname[2]);
        //task.setGroup(jobKey.getGroup());
        //task.setDesc("触发器[" + trigger.getKey() + "]");
        //task.setStartTime(trigger.getStartTime());             //开始时间
        //task.setEndTime(trigger.getEndTime());                 //结束时间
        task.setNextFireTime(trigger.getNextFireTime());         //下次触发时间
        task.setPreviousFireTime(trigger.getPreviousFireTime()); //上次触发时间
        if(trigger instanceof CronTrigger){
            task.setCron(((CronTrigger)trigger).getCronExpression());
        }
        Trigger.TriggerState triggerState = scheduler.getTriggerState(trigger.getKey());
        task.setStatus("N".equals(triggerState.name()) ? SeedConstants.QSS_STATUS_STOP : SeedConstants.QSS_STATUS_RUNNING);
        return task;
    }
}