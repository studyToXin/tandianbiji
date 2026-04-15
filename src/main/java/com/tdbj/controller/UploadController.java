package com.tdbj.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.tdbj.dto.Result;
import com.tdbj.dto.UserDTO;
import com.tdbj.entity.User;
import com.tdbj.service.IUserService;
import com.tdbj.utils.SystemConstants;
import com.tdbj.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.tdbj.utils.RedisConstants.LOGIN_USER_KEY;
import static com.tdbj.utils.RedisConstants.LOGIN_USER_TTL;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 博客图片上传（逻辑保持不变）
     */
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            String originalFilename = image.getOriginalFilename();
            // 博客依然走 blogs 目录，带 hash 子目录
            String fileName = createNewFileName(originalFilename, "blogs");
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 头像上传（修改逻辑：直接存到 /imgs/icon/ 下）
     */
    @PostMapping("avatar")
    public Result uploadAvatar(@RequestParam("file") MultipartFile image) {
        try {
            String originalFilename = image.getOriginalFilename();
            // 头像走 icon 目录，扁平化
            String fileName = createNewFileName(originalFilename, "icons");
            image.transferTo(new File(SystemConstants.EMAGE_UPLOAD_DIR, fileName));

            UserDTO userDTO = UserHolder.getUser();
            Long userId = userDTO.getId();

            User user = new User();
            user.setId(userId);
            user.setIcon(fileName);
            userService.updateById(user);

            updateRedisAvatar(fileName);

            log.debug("头像上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        File file = new File(SystemConstants.IMAGE_UPLOAD_DIR, filename);
        if (file.isDirectory()) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(file);
        return Result.ok();
    }

    /**
     * 生成唯一文件名
     */
    private String createNewFileName(String originalFilename, String folderName) {
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        String name = UUID.randomUUID().toString();

        // 【修改点】：这里只返回相对路径，不要带 /imgs 前缀
        // 因为 SystemConstants.IMAGE_UPLOAD_DIR 已经指向了 imgs 目录
        if ("icons".equals(folderName)) {
            // 头像：返回 /icon/uuid.suffix
            // 最终访问路径：/imgs/icon/uuid.suffix (配合 MvcConfig)
            return StrUtil.format("/imgs/icons/{}.{}", name, suffix);
        } else {
            // 博客：保留 hash 逻辑
            int hash = name.hashCode();
            int d1 = hash & 0xF;
            int d2 = (hash >> 4) & 0xF;
            // 返回 /blogs/d1/d2/uuid.suffix
            return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
        }
    }

    private void updateRedisAvatar(String fileName) {
        try {
            org.springframework.web.context.request.RequestAttributes ra =
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (ra != null) {
                javax.servlet.http.HttpServletRequest request =
                        ((org.springframework.web.context.request.ServletRequestAttributes) ra).getRequest();
                String token = request.getHeader("authorization");

                if (StrUtil.isNotBlank(token)) {
                    String redisKey = LOGIN_USER_KEY + token;
                    stringRedisTemplate.opsForHash().put(redisKey, "icons", fileName);
                    stringRedisTemplate.expire(redisKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
                }
            }
        } catch (Exception e) {
            log.error("更新 Redis 头像失败", e);
        }
    }
}