package com.sshareing.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.sshareing.dto.LoginFormDTO;
import com.sshareing.dto.Result;
import com.sshareing.entity.User;

import javax.servlet.http.HttpSession;


public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();
}
