# 1. 引用与你项目一致的官方 ES 镜像版本
FROM docker.elastic.co/elasticsearch/elasticsearch:8.10.4

# 2. 在构建镜像时，直接使用 curl -k (忽略SSL证书) 下载离线包到 /tmp 目录
# 这一步完美绕过了 Java 底层下载器的 SSL 报错问题
RUN curl -k -L -o /tmp/ik-8.10.4.zip https://get.infini.cloud/elasticsearch/analysis-ik/8.10.4

# 3. 从内部临时文件静默安装插件
RUN /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch file:///tmp/ik-8.10.4.zip

# 4. 删除临时压缩包，减小最终镜像的体积
RUN rm /tmp/ik-8.10.4.zip