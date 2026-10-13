package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 实现热点接口查询
     * @param current
     * @return
     */
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
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }


    /**
     * 查看探店笔记
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //查询blog有关的用户
        queryBlogUser(blog);
        //查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user==null){
            return ;
        }
        //获取当前用户
        Long userId = user.getId();
        String key="blog:liked:" + blog.getId();
        //判断当前用户是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);

    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
    /**
     * 点赞功能的实现
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key= RedisConstants.BLOG_LIKED_KEY + id;
        //判断当前用户是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //如果没有点赞，
        if (score==null) {
            //更新数据库
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            //存入redis的·set集合中  zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
        //如果已经点赞
        boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
        //数据库点赞减1
        //把用户从redis的set集合中移除
        if (isSuccess) {
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
        }
        return Result.ok();
    }
    /**
     * 实现点赞排序top5
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key= RedisConstants.BLOG_LIKED_KEY + id;
        //查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //根据id查用户
        List<UserDTO> userDTOS = userService.query().
                in("id",ids).
                last("ORDER BY FIELD(id,"+idStr+")")
                .list().
                stream().
                map(user -> BeanUtil.copyProperties(user, UserDTO.class)).
                collect(Collectors.toList());
        //返回
        return Result.ok(userDTOS);

    }

}
