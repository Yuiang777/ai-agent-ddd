package cn.apple.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author _apple_sheep_
 * @description 查询数据接口
 * @date 2026/4/28
 * @Copyright github:Yuiang777
 */
@Slf4j
@RestController
@RequestMapping("/api/query")
public class QueryController {
    @RequestMapping("/model")
    public String queryModel(){
        return "query model";
    }
}
