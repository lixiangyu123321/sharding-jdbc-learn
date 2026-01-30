package org.lix.mycatdemo.security.csrf;

import org.apache.sshd.common.channel.RequestHandler;
import org.apache.sshd.common.util.buffer.Buffer;

public class CustomCsrfTokenRequestHandler implements RequestHandler {
    @Override
    public Result process(Object o, String s, boolean b, Buffer buffer) throws Exception {
        return null;
    }
}
