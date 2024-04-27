package com.sshareing.service.impl;

import com.sshareing.entity.UserInfo;
import com.sshareing.mapper.UserInfoMapper;
import com.sshareing.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
