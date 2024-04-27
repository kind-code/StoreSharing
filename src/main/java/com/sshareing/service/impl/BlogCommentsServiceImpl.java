package com.sshareing.service.impl;

import com.sshareing.entity.BlogComments;
import com.sshareing.mapper.BlogCommentsMapper;
import com.sshareing.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;


@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
