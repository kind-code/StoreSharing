package com.sshareing.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sshareing.dto.Result;
import com.sshareing.dto.UserDTO;
import com.sshareing.entity.Follow;
import com.sshareing.mapper.FollowMapper;
import com.sshareing.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sshareing.service.IUserService;
import com.sshareing.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long id = UserHolder.getUser().getId();
        String key = "follows:"+id;
        //判断是否取关
        if(isFollow) {
            //关注。新增
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                //将 关注用户id 放入set集合
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            //取关 删除
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", id).eq("follow_user_id", followUserId));
            if(isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long id = UserHolder.getUser().getId();
        //1.查询是否关注
        Integer count = query().eq("user_id", id).eq("follow_user_id", followUserId).count();
        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long id) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:"+userId;
        String key2 = "follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect==null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> collect = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOList = userService.listByIds(collect).stream().map(user -> {
            return BeanUtil.copyProperties(user, UserDTO.class);
        }).collect(Collectors.toList());

        return Result.ok(userDTOList);
    }
}
