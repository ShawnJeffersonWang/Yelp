package com.hmdp.controller;


import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     * RequestParam表示参数写在路径里
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 发送短信验证码并保存验证码
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * 前端提交的是JSON风格，JSON的后台要接受就需要用到这个注解RequestBody
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     *                  为什么需要session, 1. 登录成功需要把用户信息存到session, 2. 验证码校验也需要用到session
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session) {
        // 实现登录功能
        return userService.login(loginForm, session);
    }

    /**
     * 登出功能
     *
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout() {
        // TODO 实现登出功能
        return Result.fail("功能未完成");
    }

    @GetMapping("/me")
    public Result me() {
        // 获取当前登录的用户并返回
        /*
        有了拦截器，所有的Controller都可以不用写登录校验，全部由拦截器来做
        但校验完成后，在后续的业务中是需要用户信息的
        所以需要将拦截器拦截得到的用户信息传递到Controller里面去
        而且在传递的过程中要注意线程安全问题
        可以使用ThreadLocal来解决，拦截到用户信息可以保存到ThreadLocal里，因为ThreadLocal是一个线程域对象
        每一个进入Tomcat的的请求都是一个独立的线程
        将来TheadLocal就会在线程内开辟一个内存空间，去保存对应的用户，这样一来每一个线程相互不干扰
        不同的用户访问Controller，都有独立线程，都有自己的用户信息，互相不干扰
        到了Controller再从ThreadLocal取出用户
         */
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }
}
