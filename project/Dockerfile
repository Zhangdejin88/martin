#基础镜像
FROM java:8

#Author
MAINTAINER lichengrun

#挂载点
#VOLUME /data

#复制文件
ADD access-router-assembly-0.2.0.7.jar  /access-router-assembly-0.2.0.7.jar

#执行命令
RUN bash -c 'touch /access-router-assembly-0.2.0.7.jar'

EXPOSE 2552 25230 


#配置容器启动后执行的命令
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","access-router-assembly-0.2.0.7.jar"]

