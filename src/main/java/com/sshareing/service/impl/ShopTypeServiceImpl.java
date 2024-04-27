package com.sshareing.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.sshareing.dto.Result;
import com.sshareing.entity.ShopType;
import com.sshareing.mapper.ShopTypeMapper;
import com.sshareing.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sshareing.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //1.从redis中查询数据
        String shopType = stringRedisTemplate.opsForValue().get(RedisConstants.SHOP_TYPE_KEY);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopType)) {
            //3.存在，则返回
            List<ShopType> shopTypes = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(shopTypes);
        }
        //4.不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if(typeList.isEmpty()){
            //6.不存在 返回错误 401
            return Result.fail("店铺类型不存在");
        }
        //5.存在，存入redis 返回数据
        String jsonStr = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(RedisConstants.SHOP_TYPE_KEY, jsonStr);
        return Result.ok(typeList);
    }
}
