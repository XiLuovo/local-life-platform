package com.sky.mapper;

import com.sky.entity.Follow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FollowMapper {

    @Insert("insert into tb_follow (user_id, follow_user_id, create_time) values (#{userId}, #{followUserId}, #{createTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Follow follow);

    @Delete("delete from tb_follow where user_id = #{userId} and follow_user_id = #{followUserId}")
    int deleteByUserAndFollowUser(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

    @Select("select count(1) from tb_follow where user_id = #{userId} and follow_user_id = #{followUserId}")
    int countByUserAndFollowUser(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

    @Select("select * from tb_follow where follow_user_id = #{followUserId}")
    List<Follow> listByFollowUserId(Long followUserId);

    @Select("select follow_user_id from tb_follow where user_id = #{userId}")
    List<Long> listFollowUserIdsByUserId(Long userId);
}
