package com.sshareing.service;

import com.sshareing.dto.Result;
import com.sshareing.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;


public interface IShopTypeService extends IService<ShopType> {

    Result queryTypeList();
}
