package com.sky.mapper;

import com.sky.entity.Blog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BlogMapper {

    @Insert("insert into tb_blog (shop_id, user_id, title, images, content, liked, comments, create_time, update_time) " +
            "values (#{shopId}, #{userId}, #{title}, #{images}, #{content}, #{liked}, #{comments}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Blog blog);

    @Select("select * from tb_blog where id = #{id}")
    Blog getById(Long id);

    @Select("select * from tb_blog order by liked desc, create_time desc")
    List<Blog> listHot();

    @Select("select * from tb_blog where user_id = #{userId} order by create_time desc")
    List<Blog> listByUserId(Long userId);

    @Select({
            "<script>",
            "select * from tb_blog where id in",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>",
            "#{id}",
            "</foreach>",
            "</script>"
    })
    List<Blog> listByIds(@Param("ids") List<Long> ids);

    @Update("update tb_blog set liked = liked + #{delta}, update_time = now() where id = #{id}")
    int updateLiked(@Param("id") Long id, @Param("delta") int delta);
}
