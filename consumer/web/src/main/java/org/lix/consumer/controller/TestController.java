package org.lix.consumer.controller;

import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.lix.provider.facade.service.TestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/test")
public class TestController {

    @DubboReference(version = "1.0.0", interfaceClass = TestService.class)
    private TestService testService;

    @PostMapping("/guess")
    public String guess(@RequestParam("name") String name) {
        long start = System.currentTimeMillis();
        String ans = testService.guessWhoAmI(name);
        long costTime = System.currentTimeMillis() - start;
        log.info("cost time: " + costTime);

        return ans;
    }
}
