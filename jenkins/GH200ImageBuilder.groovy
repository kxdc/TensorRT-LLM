@Library(['bloom-jenkins-shared-lib@main', 'trtllm-jenkins-shared-lib@main']) _

import java.lang.Exception
import groovy.transform.Field

// Docker image registry
DOCKER_IMAGE = "docker:dind"
IMAGE_NAME = "urm.nvidia.com/sw-tensorrt-docker/tensorrt-llm-staging"

// LLM repository configuration
withCredentials([string(credentialsId: 'default-llm-repo', variable: 'DEFAULT_LLM_REPO')]) {
    LLM_REPO = env.gitlabSourceRepoHttpUrl ? env.gitlabSourceRepoHttpUrl : "${DEFAULT_LLM_REPO}"
}
LLM_ROOT = "llm"

def buildImage(action, type)
{
    def branch = env.gitlabBranch
    def branchTag = branch.replaceAll('/', '_')
    def buildNumber = env.hostBuildNumber ? env.hostBuildNumber : BUILD_NUMBER
    def stage_docker = "tritondevel"
    def tag = "sbsa-${stage_docker}-torch_${type}-${branchTag}-${buildNumber}"

    // Step 1: cloning tekit source code
    // allow to checkout from forked repo, svc_tensorrt needs to have access to the repo, otherwise clone will fail
    stage('Prepare') {
        echo "hostJobName: ${env.hostJobName}"
        echo "hostBuildNumber: ${env.hostBuildNumber}"
        echo "gitlabBranch: ${env.gitlabBranch}"
        echo "action: ${env.action}"
        echo "type: ${env.type}"

        sh 'pwd'
        sh 'ls -lah'
        sh 'rm -rf ./*'
        sh 'ls -lah'
    }

    trtllm_utils.checkoutSource(LLM_REPO, branch, LLM_ROOT, true, true)

    // Step 2: building wheels in container
    docker.image(DOCKER_IMAGE).inside('-v /var/run/docker.sock:/var/run/docker.sock --privileged') {
        stage ("Install packages") {
            sh "pwd && ls -alh"
            sh "env"
            sh "apk add make git"
            sh "git config --global --add safe.directory '*'"

            withCredentials([usernamePassword(credentialsId: "urm-artifactory-creds", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                sh "docker login urm.nvidia.com -u ${USERNAME} -p ${PASSWORD}"
            }

            withCredentials([
                usernamePassword(
                    credentialsId: "svc_tensorrt_gitlab_read_api_token",
                    usernameVariable: 'USERNAME',
                    passwordVariable: 'PASSWORD'
                ),
                string(credentialsId: 'default-git-url', variable: 'DEFAULT_GIT_URL')
            ]) {
                sh "docker login ${DEFAULT_GIT_URL}:5005 -u ${USERNAME} -p ${PASSWORD}"
            }
        }
        try {
            containerGenFailure = null
            // stage ("Generate Image") {
            //     retry(3)
            //     {
            //         sh "cd ${LLM_ROOT} && make -C docker release_build TORCH_INSTALL_TYPE=${type}" +
            //            " GITHUB_MIRROR=https://urm.nvidia.com/artifactory/github-go-remote"
            //     }
            // }
            stage ("Perform '${action}' action on image") {
                retry(3)
                {
                    sh """cd ${LLM_ROOT} && make -C docker ${stage_docker}_${action} \
                        IMAGE_NAME=${IMAGE_NAME} \
                        IMAGE_TAG=${tag} \
                        TORCH_INSTALL_TYPE=${type} \
                        GITHUB_MIRROR=https://urm.nvidia.com/artifactory/github-go-remote"""
                }
            }
        } catch (Exception ex) {
            containerGenFailure = ex
        } finally {
            stage ("Docker logout") {
                withCredentials([string(credentialsId: 'default-git-url', variable: 'DEFAULT_GIT_URL')]) {
                    sh "docker logout urm.nvidia.com"
                    sh "docker logout ${DEFAULT_GIT_URL}:5005"
                }
            }
            if (containerGenFailure != null) {
                throw containerGenFailure
            }
        }
    }
}


pipeline {
    agent {
        label 'sbsa-a100-80gb-pcie-x4||sbsa-gh200-480gb'
    }
    options {
        // Check the valid options at: https://www.jenkins.io/doc/book/pipeline/syntax/
        // some step like results analysis stage, does not need to check out source code
        skipDefaultCheckout()
        // to better analyze the time for each step/test
        timestamps()
        timeout(time: 24, unit: 'HOURS')
    }
    environment {
        PIP_INDEX_URL="https://urm.nvidia.com/artifactory/api/pypi/pypi-remote/simple"
    }
    stages {
        stage("Build")
        {
            steps
            {
                buildImage(env.action, env.type)
            }
        }
    } // stages
} // pipeline
