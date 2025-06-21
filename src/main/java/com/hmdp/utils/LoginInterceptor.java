package com.hmdp.utils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.Session;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import lombok.val;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;


import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)) {
            response.setStatus(401);
            return false;
        }


        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash()
                .entries(key);
        if(userMap.isEmpty()) {
            response.setStatus(401);
            return false;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
//        UserDTO userDTO = new UserDTO();
//        BeanUtils.populate(userDTO, userMap);
        UserHolder.saveUser(userDTO);
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
