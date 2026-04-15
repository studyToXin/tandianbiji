package com.tdbj.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tdbj.dto.LoginFormDTO;
import com.tdbj.dto.Result;
import com.tdbj.dto.UserDTO;
import com.tdbj.entity.User;
import com.tdbj.mapper.UserMapper;
import com.tdbj.service.IUserService;
import com.tdbj.utils.RegexUtils;
import com.tdbj.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.tdbj.utils.RedisConstants.*;
import static com.tdbj.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 * */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送手机验证码
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 session
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }
    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 不一致，报错
            return Result.fail("验证码错误");
        }
        // 4.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();
        // 5.判断用户是否存在
        if (user == null) {
            // 6.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 7.保存用户信息到 redis中
        // 7.1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 7.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8.返回token
        return Result.ok(token);
    }
    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:5:202203 GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6.循环遍历
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字的最后一个bit位  // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器+1
                count++;
            }
            // 把数字右移一位，抛弃最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }

    @Override
    public Result logout(HttpServletRequest request){
        //1.获取用户昵称
        String name = UserHolder.getUser().getNickName();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.获取请求头中的token
        String token = request.getHeader("authorization");
        //4.拼接对应redis中token的位置login: token: xxxxx
        String tokenKey = LOGIN_USER_KEY + token;
        //4.删除redis中的token
        stringRedisTemplate.delete(tokenKey);
        log.info(now+" 用户: {"+name+"} 退出成功！");
        UserHolder.removeUser();
        return Result.ok();
    }

    @Override
    public void updateUser(UserDTO userDTO) {
        // 1. 获取当前登录用户 ID
        Long userId = UserHolder.getUser().getId();

        // 2. 创建更新对象
        User user = new User();
        user.setId(userId);

        // 3. 判断并设置需要更新的字段
        if (StrUtil.isNotBlank(userDTO.getNickName())) {
            user.setNickName(userDTO.getNickName());
        }
        if (StrUtil.isNotBlank(userDTO.getIcon())) {
            user.setIcon(userDTO.getIcon());
        }

        // 4. 更新数据库
        updateById(user);

        // 5. 【核心修复】同步更新 Redis
        try {
            // 5.1 获取当前请求对象
            org.springframework.web.context.request.RequestAttributes ra =
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (ra != null) {
                javax.servlet.http.HttpServletRequest request =
                        ((org.springframework.web.context.request.ServletRequestAttributes) ra).getRequest();

                // 5.2 从请求头获取 Token (注意：这里假设前端传的是 "authorization")
                String token = request.getHeader("authorization");

                if (StrUtil.isNotBlank(token)) {
                    // 5.3 拼接 Redis Key
                    String redisKey = LOGIN_USER_KEY + token;

                    // 5.4 重新查询数据库，获取最新的完整用户信息
                    User dbUser = getById(userId);
                    UserDTO newDto = BeanUtil.copyProperties(dbUser, UserDTO.class);

                    // 5.5 转为 Map (这一步和你的 login 方法逻辑完全一致)
                    Map<String, Object> userMap = BeanUtil.beanToMap(newDto, new HashMap<>(),
                            CopyOptions.create()
                                    .setIgnoreNullValue(true)
                                    .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

                    // 5.6 覆盖 Redis 中的数据
                    stringRedisTemplate.opsForHash().putAll(redisKey, userMap);

                    // 5.7 刷新 UserHolder (防止本次请求后续逻辑用到旧数据)
                    UserHolder.saveUser(newDto);
                }
            }
        } catch (Exception e) {
            // 防止因为获取 Request 失败导致业务中断，记录日志即可
            log.error("同步更新 Redis 用户信息失败", e);
        }
    }
    }
