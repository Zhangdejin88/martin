pipeline{
      // 定义groovy脚本中使用的环境变量
      environment{
        // 本示例中使用DEPLOY_TO_K8S变量来决定把应用部署到哪套容器集群环境中，如“Production Environment”， “Staging001 Environment”等
        PACK_ENV =  sh(returnStdout: true,script: 'echo $pack_env').trim()
        GIT_ADDR =  sh(returnStdout: true,script: 'echo $git_addr').trim()
        REPO =  sh(returnStdout: true,script: 'echo $repo').trim()
        BRANCH =  sh(returnStdout: true,script: 'echo $branch').trim()
        PROJECT_DIR =  sh(returnStdout: true,script: 'echo $project_dir').trim()
        PROJECT_ITEM_NAME = sh(returnStdout: true,script: 'echo $project_item_name').trim()
        NAME_SPACE = sh(returnStdout: true,script: 'echo $name_space').trim()
        LIM_CPU = sh(returnStdout: true,script: 'echo $lim_cpu').trim()
        LIM_MEM = sh(returnStdout: true,script: 'echo $lim_mem').trim()
        REQ_CPU = sh(returnStdout: true,script: 'echo $req_cpu').trim()
        REQ_MEM = sh(returnStdout: true,script: 'echo $req_mem').trim()
        JVM_OPTS = sh(returnStdout: true,script: 'echo $jvm_opts').trim()
        REPLICAS_1 = sh(returnStdout: true,script: 'echo $REPLICAS').trim()
      }

      // 定义本次构建使用哪个标签的构建环境，本示例中为 “slave-pipeline”
      agent{
        node{
          label 'slave-pipeline'
        }
      }

      // "stages"定义项目构建的多个模块，可以添加多个 “stage”， 可以多个 “stage” 串行或者并行执行
      stages{
        // 定义第一个stage， 完成克隆源码的任务
        stage('Git'){
          steps{
            // git branch: '${BRANCH}', credentialsId: 'a9d35821-1a96-4aa8-9221-829c46b98a3e', url: '${GIT_ADDR}'
            git branch: '${BRANCH}',  url: '${GIT_ADDR}'
            script {
                IMAGE_TAG =  sh(returnStdout: true,script: 'git rev-parse --short HEAD').trim()
            }
          }
        }

        // 添加第二个stage， 运行源码打包命令
        stage('Package'){
          steps{
              container("sbt") {
                  sh "/usr/local/sbt/sbt assembly   -P $PACK_ENV"
                  sh 'pwd;ls'
              }
          }`
        }

        // 添加第四个stage, 运行容器镜像构建和推送命令， 用到了environment中定义的groovy环境变量
        stage('Image Build And Publish'){
          environment{
              IMAGE_ID = "${IMAGE_TAG}"
          }
          steps{
              container("kaniko") {
                  sh "cd $PROJECT_DIR && kaniko -f `pwd`/src/main/docker/Dockerfile -c `pwd`/target/ --destination=registry-vpc.cn-hangzhou.aliyuncs.com/${REPO}/${PROJECT_ITEM_NAME}:${IMAGE_ID}"
              }
          }
        }

        stage('Deploy to Kubernetes') {
          environment{
              IMAGE_ID = "${IMAGE_TAG}"
          }
            parallel {
                stage('Deploy to Production Environment') {
                    when {
                        expression {
                            "$PACK_ENV" == "生产环境"
                        }
                    }
                    steps {
                        container('kubectl') {
                            kubernetesDeploy (configs: 'deployment.yaml', kubeconfigId: 'kubeconfigPre')
                        }
                    }
                }
                stage('Deploy to Prerelease Environment') {
                    when {
                        expression {
                            "$PACK_ENV" == "预发布环境"
                        }
                    }
                    steps {
                        container('kubectl') {
                            git branch: 'master', credentialsId: 'd14b9ab1-5a2f-415a-a9b2-a4b6a5fda06e', url: 'http://gitlab.mamcharge.com/x_FT/jenkinsfile.git'
                            sh 'pwd;ls'
                            kubernetesDeploy (configs: 'deployment.yaml', kubeconfigId: 'kubeconfigPre')
                        }
                    }
                }
            }
        }
      }
    post {
        success {
            script {
                def user_email = getBuildUser().BUILD_USER_EMAIL
                sh("curl -X POST 'https://oapi.dingtalk.com/robot/send?access_token=404fffc3e4bc476dc26c93df373eebffa12712bd14a16161675ecc114716d3d5' \
                -H 'cache-control: no-cache' \
                -H 'content-type: application/json' \
                -d '{\"msgtype\": \"markdown\", \"markdown\": {\"title\": \"Jenkins构建和发布\",\"text\":\"### 构建成功!发布任务已提交至K8S!  \n ### 构建环境: $PACK_ENV \n  ### 构建项目名称 : $JOB_BASE_NAME \n ### 构建编号和详情 : [\'$BUILD_DISPLAY_NAME\']($RUN_DISPLAY_URL) \n  ### 项目发布详情 : [\'点此查看详情\'](https://cs.console.aliyun.com/#/k8s/deployment/detail/cn-hangzhou/c12de2bb7633a43c4b6567695b3be4369/$NAME_SPACE/$PROJECT_ITEM_NAME/pods) \n  ### 代码版本CommitID : \'${IMAGE_TAG}\' \n  ### 任务启动人 : @\'${user_email}\'\"},\"at\": {\"atMobiles\": [\'${user_email}\']} }'")
            }
        }
        failure {
            script {
                def user_email = getBuildUser().BUILD_USER_EMAIL
                sh("curl -X POST 'https://oapi.dingtalk.com/robot/send?access_token=404fffc3e4bc476dc26c93df373eebffa12712bd14a16161675ecc114716d3d5' \
                -H 'cache-control: no-cache' \
                -H 'content-type: application/json' \
                -d '{\"msgtype\": \"markdown\", \"markdown\": {\"title\": \"Jenkins构建和发布\",\"text\":\"### 任务失败!  \n ### 构建环境: $PACK_ENV \n  ### 构建项目名称 : $JOB_BASE_NAME \n ### 构建编号和详情 : [\'$BUILD_DISPLAY_NAME\']($RUN_DISPLAY_URL)  \n  ### 代码版本CommitID : \'${IMAGE_TAG}\' \n  ### 任务启动人 : @\'${user_email}\'\"},\"at\": {\"atMobiles\": [\'${user_email}\']} }'")
            }
        }
    }
}
