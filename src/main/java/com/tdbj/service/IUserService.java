package com.tdbj.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.tdbj.dto.LoginFormDTO;
import com.tdbj.dto.Result;
import com.tdbj.dto.UserDTO;
import com.tdbj.entity.User;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 * */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();

    Result logout(HttpServletRequest request);

    void updateUser(UserDTO userDTO);
}
