package com.sshareing.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sshareing.dto.LoginFormDTO;
import com.sshareing.dto.Result;
import com.sshareing.dto.UserDTO;
import com.sshareing.entity.User;
import com.sshareing.mapper.UserMapper;
import com.sshareing.service.IUserService;
import com.sshareing.utils.RegexUtils;
import com.sshareing.utils.SystemConstants;
import com.sshareing.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.sshareing.utils.RedisConstants.*;


@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

//        //4.保存验证码到session
//        session.setAttribute("code",code);

        //4.保存验证码到redis // set key value ex 120
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码
        log.debug("发送验证码成功，验证码：{}",code);
        //6.返回OK
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }
        //2.验证码
//        Object cacheCode = session.getAttribute("code");
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        //3.不一致。报错
        if (cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户 select * from tb_user where phone = #{phone}
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if (user == null) {
            //6.不存在，创建用户并保存
           user = crateUserWithPhone(phone);

        }
        //7.存在 保存用户到session中
        //保存到Redis中
        // 随机生成token 作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 将User对象转为 map
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                                                            CopyOptions.create()
                                                            .setIgnoreNullValue(true)
                                                            .setFieldValueEditor((filedName,fieldValue)->fieldValue.toString()));
        //存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token,userMap);
        //设置有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL,TimeUnit.SECONDS);
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY +userId+keySuffix;
        //4.获取今天是本月的第几天
        int day = now.getDayOfMonth();
        //5.写入redis setbit key offset 1
        stringRedisTemplate.opsForValue().setBit(key,day-1,true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
         //1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY +userId+keySuffix;
        //4.获取今天是本月的第几天
        int day = now.getDayOfMonth();
        //5.返回十进制数据
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands
                                .BitFieldType
                                .unsigned(day))
                        .valueAt(0));
        if (result==null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num==null || num == 0) {
            return Result.ok(0);
        }
        //6.循环遍历
        int count = 0;
        while (true) {
            //7.与1做与运算 得到数字最后一个bit位
            if ((num & 1)==0) {
                //0 表示 结束
                break;
            }else {
                //1 计数器 + 1
                count++;
            }
            //数字右移
            num >>>= 1;

        }
        return Result.ok(count);
    }

    private User crateUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
