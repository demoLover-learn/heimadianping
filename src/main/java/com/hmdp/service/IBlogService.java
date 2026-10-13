package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {
    /**
     * 查询探店笔记
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 获取热点信息
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 点赞功能的实现
     * @param id
     * @return
     */
    Result likeBlog(Long id);
    /**
     * 实现点赞排序top5
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);
}
