package cn.apple.application.multimodal;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ModelCapabilityService {

    public List<ModelCapability> capabilities() {
        return List.of(
                new ModelCapability("vision-chat", "图片理解", "image_in,text_out", "通过 MiniMax Token Plan MCP 的 understand_image 工具识别图片，需要绑定 minimax-image-understand MCP", true, LocalDateTime.now()),
                new ModelCapability("image-gen", "图片生成", "text_in,image_out", "用于文生图、图片改写提示词生成", true, LocalDateTime.now()),
                new ModelCapability("video-understand", "视频理解", "video_in,text_out", "用于视频抽帧、场景摘要、时间轴理解", false, LocalDateTime.now()),
                new ModelCapability("video-gen", "视频生成", "text_in,video_out", "用于文生视频、分镜生成和异步视频任务", false, LocalDateTime.now())
        );
    }

    public record ModelCapability(String capabilityId,
                                  String capabilityName,
                                  String modalities,
                                  String description,
                                  boolean mvpReady,
                                  LocalDateTime createTime) {
    }
}

