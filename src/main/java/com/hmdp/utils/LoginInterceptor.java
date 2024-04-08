package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import io.netty.util.internal.StringUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

// 要想让拦截器生效还需要配置拦截器
public class LoginInterceptor implements HandlerInterceptor {

    // 这里不能用Autowired, Resource等注解，只能使用构造函数来注入
    // 因为LoginInterceptor这个类他的对象是我们自己手动new出来的，不是通过Component等等注解来构建的
    // 也就是说这个类的对象不是由Spring创建的, 是我们手动创建的，由Spring创建的对象能帮我们做依赖注入，比如加Autowired
    // 但是自动手动创建的对象没有人帮我们做依赖注入，利用构造函数来注入，谁来注入，就看是谁用了它
//    private StringRedisTemplate stringRedisTemplate;
//
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    // preHandle前置拦截，postHandle在 Controller执行之后
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        // (deprecated) 1. 获取session
//        // 1. 获取请求头中的token
//        String token = request.getHeader("authorization");
//        if (StrUtil.isBlank(token)) {
//            // 不存在，拦截 返回 401 状态码
//            response.setStatus(401);
//            return false;
//        }
////        HttpSession session = request.getSession();
//
//
//        // (deprecated) 2. 获取session中的用户
////        Object user = session.getAttribute("user");
//
//        // 2. 基于TOKEN获取redis中的用户
//        String key = RedisConstants.LOGIN_USER_KEY + token;
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
//                .entries(key);
//        // 3. 判断用户是否存在
//        // user.null
//        if (userMap.isEmpty()) {
//            // 4. 不存在，拦截 返回 401 状态码
//            response.setStatus(401);
//            return false;
//        }
//        // 5. 将查询到的Hash数据转为UserDTO对象
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        // 6. 存在，保存用户信息到 ThreadLocal 不需要key, 直接保存在当前线程里
//        UserHolder.saveUser(userDTO);
//
//        // 7. 刷新token有效期
//        // 有一个问题，拦截器只能拦截需要登录的路径，就导致不需要登录的页面拦截器不生效，也就不会刷新，所以还需要一个拦截器
//        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
//        // 8. 放行

        // 1. 判断是否需要拦截(ThreadLocal中是否有用户)
        if (UserHolder.getUser() == null) {
            // 没有，需要拦截，设置状态码
            response.setStatus(401);
            // 拦截
            return false;
        }
        // 有用户，则放行
        return true;
        // 其余动作全部放在刷新拦截器
    }
}
