package com.sshareing.config;

import com.sshareing.utils.LoginInterceptor;
import com.sshareing.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录拦截器
       registry.addInterceptor(new LoginInterceptor())
               .excludePathPatterns("/user/login",
                       "/user/code",
                       "/blog/hot",
                       "/shop/**",
                       "/shop-type/**",
                       "/voucher/**",
                       "/upload/**").order(1);
       //刷新拦截器
       registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
