package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.servlet.HandlerInterceptor;

// 要想让拦截器生效还需要配置拦截器
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 获取session
        HttpSession session = request.getSession();
        // 2. 获取session中的用户
        Object user = session.getAttribute("user");
        // 3. 判断用户是否存在
        // user.null
        if (user == null) {
            // 4. 不存在，拦截 返回 401 状态码
            response.setStatus(401);
            return false;
        }
        // 5. 存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser((UserDTO) user);
        // 6. 放行
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户，避免内存泄漏
        // 保存的时候不需要key，它是保存在当前线程里面的
        UserHolder.removeUser();
    }
}
