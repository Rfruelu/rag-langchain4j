package com.lujia.rag.raglangchain4j.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lujia.rag.raglangchain4j.auth.entity.UserInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserInfoMapper extends BaseMapper<UserInfo> {
}
