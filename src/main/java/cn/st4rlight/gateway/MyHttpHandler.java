package cn.st4rlight.gateway;

import cn.hutool.core.io.resource.BytesResource;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.http.HttpResponse;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.concurrent.FutureListener;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 该handler，由于使用了HttpServerCodec以及HttpObjectAggregator，因此所接收的数据类型为FullHttpRequest
 * 对于/api请求，重新构造出http请求并转发到后端中
 * 对于/请求，读取并返回index.html文件
 *
 * @author st4rlight
 * @since 2020/8/6
 */
@Slf4j
public class MyHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final String TARGET_HOST = "localhost";
    private final int TARGET_PORT = 7749;
    private final String FRONT_PATH = "/root/my-react-blog-front/build";
    private final String INDEX_NAME = "index.html";
    private final String SERVER_NAME = "st4rlight-netty-gateway/1.0.0";

    /**
     * 核心方法，读取并转发
     */
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        HttpMethod reqMethod = msg.method();
        String reqUri = msg.uri();
        Map<String, List<String>> headerMap = getHeaderMap(msg.headers());

        // api请求转发至后端端口
        if(reqUri.startsWith("/api")){
            HttpResponse httpResponse = null;
            HttpHeaders httpHeaders = new DefaultHttpHeaders();
            String targetUrl = TARGET_HOST + ":" + TARGET_PORT + reqUri;

            log.info("url: {}", targetUrl);

            if(reqMethod == HttpMethod.GET) {
                cn.hutool.http.HttpRequest httpRequest
                        = cn.hutool.http.HttpRequest.get(targetUrl)
                                .header(headerMap);

                httpResponse = httpRequest.execute();
            }
            if(reqMethod == HttpMethod.POST){
                cn.hutool.http.HttpRequest httpRequest
                        = cn.hutool.http.HttpRequest.post(targetUrl)
                                .form(getFormMap(msg))
                                .header(headerMap);

                httpResponse = httpRequest.execute();
            }


            Map<String, List<String>> headers = httpResponse.headers();
            headers.keySet().forEach(item -> {
                if(ObjectUtil.isNotEmpty(item))
                    httpHeaders.add(item, headers.get(item));
            });
            DefaultFullHttpResponse myResponse = new DefaultFullHttpResponse(
                    HttpVersion.valueOf(httpResponse.httpVersion()),
                    HttpResponseStatus.valueOf(httpResponse.getStatus()),
                    Unpooled.copiedBuffer(httpResponse.body().getBytes()),
                    httpHeaders,
                    new DefaultHttpHeaders()
            );
            ctx.writeAndFlush(myResponse);

        }else{
            String filePath = "";
            if("/".equals(reqUri))
                filePath = FRONT_PATH + "/" + INDEX_NAME;
            else
                filePath = FRONT_PATH + reqUri;

            File file = new File(filePath);
            FileChannel channel = new FileInputStream(file).getChannel();
            ByteBuffer buffer = ByteBuffer.allocate(Long.valueOf(channel.size()).intValue());
            channel.read(buffer);

            log.info("url: {}", reqUri);

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    Unpooled.copiedBuffer(buffer.array())
            );


            HttpHeaders headers = response.headers();
            headers.set(HttpHeaders.Names.CONTENT_TYPE, getMime(filePath));
            headers.set(HttpHeaders.Names.CONTENT_LENGTH, file.length());
            headers.set(HttpHeaders.Names.DATE, new Date(file.lastModified()));
            headers.set(HttpHeaders.Names.SERVER, SERVER_NAME);
            headers.set(HttpHeaders.Names.CONNECTION, "keep-alive");


            channel.close();
            ctx.writeAndFlush(response);
        }
    }


    /**
     * 蒋header重新封装成hutool需要的格式
     * @param httpHeaders
     * @return
     */
    public Map<String, List<String>> getHeaderMap(HttpHeaders httpHeaders){
        Iterator<Map.Entry<String, String>> iterator = httpHeaders.iterator();
        Map<String, List<String>> map = new HashMap<>();

        while (iterator.hasNext()){
            Map.Entry<String, String> next = iterator.next();
            List<String> strings = map.get(next.getKey());

            if (ObjectUtil.isNull(strings)) {
                strings = new ArrayList<>();
                map.put(next.getKey(), strings);
            }

            strings.add(next.getValue());
        }

        return map;
    }


    /**
     * 统一异常处理
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }


    /**
     * 从request中读取出所有的表单，并重新构造出表单
     * @param request 请求
     * @return 返回hutools所需的表单格式
     * @throws IOException
     */
    public Map<String, Object> getFormMap(FullHttpRequest request) throws IOException {
        HttpPostRequestDecoder decoder = new HttpPostRequestDecoder(request);
        Map<String, Object> map = new HashMap<>();

        while (decoder.hasNext()) {
            InterfaceHttpData httpData = decoder.next();
            if (httpData instanceof Attribute) {
                Attribute attr = (Attribute)httpData;
                map.put(attr.getName(), attr.getValue());

            } else if (httpData instanceof FileUpload) {
                FileUpload fileUpload = (FileUpload)httpData;
                BytesResource bytesResource = new BytesResource(fileUpload.get(), fileUpload.getFilename());

                map.put(fileUpload.getName(), bytesResource);
            }
        }

        decoder.destroy();
        return map;
    }


    /**
     * 获取文件的Mime
     */
    public String getMime(String filePath) throws IOException {
        Path path = Paths.get(filePath);
        String mime = Files.probeContentType(path);

        return mime + "; charset=UTF-8";
    }
}
