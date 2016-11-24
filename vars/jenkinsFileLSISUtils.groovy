#!/usr/bin/groovy
def tokens = "${env.JOB_NAME}".tokenize('/')
def org = tokens[tokens.size() - 3]
def repo = tokens[tokens.size() - 2]
def branch = tokens[tokens.size() - 1]

def setSlack_channel(slack_channel) {
    this.slack_channel = slack_channel
}

def getSlack_channel() {
    return this.slack_channel
}

def setBuild_docker_image(build_docker_image) {
    this.build_docker_image = build_docker_image
}

def setPull_registry(pull_registry) {
    this.pull_registry = pull_registry
}

def setPrivate_registry(private_registry) {
    this.private_registry = private_registry
}

def setPublic_registry(public_registry) {
    this.public_registry = public_registry
}

def setPom(pom) {
    this.pom = pom
}

def setGitRemote(gitRemote) {
    this.getRemore = gitRemote
}

def init() {
    checkout scm
    this.pom = readMavenPom file: 'pom.xml'
    this.gitRemote = sh(returnStdout: true, script: 'git remote get-url origin|cut -c9-').trim()
}

def build(build_docker_image, UTLN_USERNAME, UTLN_PASSWORD) {
    stage('Build') {
        docker.image(build_docker_image)
                .inside(
                "-e http_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                        "-e https_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                        '-e DOCKER_HOST=tcp://172.18.0.1:2375 ' +
                        '-v /var/run/docker.sock:/var/run/docker.sock ' +
                        '-v /home/jenkins/.m2:/home/user/.m2 ' +
                        '-v /home/jenkins/.docker:/home/user/.docker ') {
            sh "mvn --settings /home/user/.m2/settings.xml " +
                    "-Duser.home=/home/user " +
                    "-B " +
                    "-Ddocker.host=tcp://172.18.0.1:2375 " +
                    "-Ddocker.username=${UTLN_USERNAME} " +
                    "-Ddocker.password=${UTLN_PASSWORD} " +
                    "-Ddocker.pull.registry=${pull_registry} " +
                    "-Ddocker.buildArg.http_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                    "-Ddocker.buildArg.https_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                    "-Ddocker.buildArg.no_proxy=hub-docker.lsis.univ-tln.fr,.univ-tln.fr " +
                    "package"
        }
        this.slackSend channel: slack_channel,
                color: "good",
                message: "[<${env.BUILD_URL}|${this.pom.groupId}-${this.pom.artifactId}:${this.pom.version}>] builded."
    }
}

def deploy() {
    stage('Deploy') {
        withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                          credentialsId   : 'bruno.utln',
                          usernameVariable: 'UTLN_USERNAME',
                          passwordVariable: 'UTLN_PASSWORD']]) {
            docker.image(build_docker_image)
                    .inside(
                    "-e http_proxy=http://${env.UTLN_USERNAME}:${env.UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                            "-e https_proxy=http://${env.UTLN_USERNAME}:${env.UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                            '-e DOCKER_HOST=tcp://172.18.0.1:2375 ' +
                            '-v /var/run/docker.sock:/var/run/docker.sock ' +
                            '-v /home/jenkins/.m2:/home/user/.m2 ' +
                            '-v /home/jenkins/.docker:/home/user/.docker ') {
                sh "mvn --settings /home/user/.m2/settings.xml " +
                        "-Duser.home=/home/user " +
                        "-B " +
                        "-Ddocker.host=tcp://172.18.0.1:2375 " +
                        "-Ddocker.username=${env.UTLN_USERNAME} " +
                        "-Ddocker.password=${env.UTLN_PASSWORD} " +
                        "-Ddocker.pull.registry=${pull_registry} " +
                        "-Ddocker.push.registry=${private_registry} " +
                        "-Ddocker.buildArg.http_proxy=http://${env.UTLN_USERNAME}:${env.UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                        "-Ddocker.buildArg.https_proxy=http://${env.UTLN_USERNAME}:${env.UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                        "-Ddocker.buildArg.no_proxy=hub-docker.lsis.univ-tln.fr,.univ-tln.fr " +
                        "deploy"
            }
        }
        slackSend channel: slack_channel,
                color: "good",
                message: "[<${env.BUILD_URL}|${pom.groupId}-${pom.artifactId}:${pom.version}>] pushed."
    }
}

def tag() {
    stage('Tag to Github') {
        withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                          credentialsId   : 'JenkinslsisGithub',
                          usernameVariable: 'GIT_USERNAME',
                          passwordVariable: 'GIT_PASSWORD']]) {
            sh """
       git tag jenkins-${pom.artifactId}-${pom.version}-${env.BUILD_NUMBER}
       git push https://${env.GIT_USERNAME}:${env.GIT_PASSWORD}@${gitRemote}  --tags
       """
        }
    }
}