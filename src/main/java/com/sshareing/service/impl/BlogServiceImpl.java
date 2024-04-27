package com.sshareing.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sshareing.dto.Result;
import com.sshareing.dto.ScrollResult;
import com.sshareing.dto.UserDTO;
import com.sshareing.entity.Blog;
import com.sshareing.entity.Follow;
import com.sshareing.entity.User;
import com.sshareing.mapper.BlogMapper;
import com.sshareing.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.sshareing.service.IFollowService;
import com.sshareing.service.IUserService;
import com.sshareing.utils.RedisConstants;
import com.sshareing.utils.SystemConstants;
import com.sshareing.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //2.查询blog有关的用户
       queryBlogUser(blog);
        //3.查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user==null)
            //用户未登录
            return;
        Long userId = user.getId();
        Long id = blog.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.isBlogLiked(blog);
            this.queryBlogUser(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //判断当前用户是否已经点赞
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //未点赞  可以点赞
            //数据库 点数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //用户保存至redis set集合中
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //已点赞  取消点赞
            //数据库 点数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //把数据库从redis中移除
            stringRedisTemplate.opsForZSet().remove(key,userId.toString());
        }


        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1.查询top5点赞用户 zrange key 0 4
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //解析出其中的用户id
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty())
            return Result.ok(Collections.emptyList());
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        //根据id查询用户
//        List<UserDTO> userDTOList = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class) ).collect(Collectors.toList());
        String str = StrUtil.join(",", ids);
        List<UserDTO> userDTOList = userService.query().in("id",ids).last("order by field(id,"+str+")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)
        ).collect(Collectors.toList());
        //f返回

        return Result.ok(userDTOList);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            Result.fail("博客保存失败");
        }
            //查询笔记作者的所有粉丝  select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //推送笔记id给所有粉丝
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            //推送
            String key = RedisConstants.FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        //返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱
        String key = RedisConstants.FEED_KEY + userId;
        //3.解析数据：blog minitime offset
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate
                                                            .opsForZSet()
                                                            .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        //非空判断
        if(typedTuples == null || typedTuples.isEmpty())
            return Result.ok();
        ArrayList<Long> list = new ArrayList<>(typedTuples.size());
        //4.根据id查询blogId minTime offset
        long mintime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String value = typedTuple.getValue();
            list.add(Long.valueOf(value));
            long time = typedTuple.getScore().longValue();
            if(time == mintime){
                os++;
            }else {
                mintime = time;
                os = 1;
            }
        }
        String str = StrUtil.join(",", list);
        List<Blog> blogs = query().in("id", list).last("order by field(id," + str + ")").list();
        for (Blog blog : blogs) {
            queryBlogUser(blog);//查询与blog 有关的用户
            isBlogLiked(blog);//查询是否被点赞
        }
        
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(mintime);

        //5.封装并返回
        return Result.ok(scrollResult);
    }
}
