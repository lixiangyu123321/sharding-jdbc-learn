package org.lix.provider.service.dubbo;

import org.apache.dubbo.config.annotation.DubboService;
import org.lix.provider.facade.service.TestService;
import org.springframework.stereotype.Service;

@Service
@DubboService(version = "1.0.0", interfaceClass = TestService.class)
public class TestServiceImpl implements TestService {
    @Override
    public String guessWhoAmI(String name) {
        if(name.contains("lixiangyu")){
            return "yes";
        }
        return "no";
    }
}
