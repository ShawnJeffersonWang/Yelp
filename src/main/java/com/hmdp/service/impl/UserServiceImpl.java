package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        // 3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4. 保存验证码到session
        session.setAttribute("code", code);
        // 5. 发送验证码
        log.debug("发送短信验证码成功，验证码: {}", code);
        // 返回ok
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号 为什么还要校验手机号？ 这是两个不同的请求！每个请求都要做独立的校验
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2. 如果不符合，返回错误信息
            return Result.fail("手机号格式错误!");
        }
        // 2. 校验验证码 存的时候叫code 取的时候也要叫code
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        // 为什么要反向的校验，因为这种校验不需要if的嵌套，避免if的嵌套越来越深，形成菱形(钻石型)的代码
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            // 3. 不一致，报错
            return Result.fail("验证码错误");
        }
        // 4. 一致，根据手机号查询用户 select * from tb_user where phone = ?  ( 真实情况不能写select(*) )
        // 终于知道eq简写是啥了，等于就是eq, where条件是=
        User user = query().eq("phone", phone).one();

        // 5. 判断用户是否存在
        if (user == null) {
            // 6. 不存在，创建新用户并保存
            // 确保user一定有值
            user = createUserWithPhone(phone);
        }

        // 7. 保存用户信息到session中
        session.setAttribute("user", user);
        // 不需要返回用户凭证
        return null;
    }

    private User createUserWithPhone(String phone) {
        // 1. 创建用户
        User user = new User();
        user.setPhone(phone);
        // 防止用户名称非常混乱，加一个统一的前缀, 直接写user_也不太优雅，需要定义为一个常量
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2. 保存用户
        save(user);
        return user;
    }
}