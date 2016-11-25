#!groovy
def tokens = "${env.JOB_NAME}".tokenize('/')
def org = tokens[tokens.size() - 3]
def repo = tokens[tokens.size() - 2]
def branch = tokens[tokens.size() - 1]

def slack_channel

def setSlackChannel(slackChannel) {
    this.slackChannel = slackChannel
}

def getSlackChannel() {
    return this.slackChannel
}

def setMavenDockerImage(mavenDockerImage) {
    this.mavenDockerImage = mavenDockerImage
}

def setPullRegistry(pullRegistry) {
    this.pullRegistry = pullRegistry
}

def setPrivateRegistry(privateRegistry) {
    this.privateRegistry = privateRegistry
}

def setPublicRegistry(publicRegistry) {
    this.publicRegistry = publicRegistry
}

def setPom(pom) {
    this.pom = pom
}

def setGitRemote(gitRemote) {
    this.getRemore = gitRemote
}

def init() {
    slackSend channel: this.slackChannel,
            color: "good",
            message: "Build starting. <${env.BUILD_URL}|${env.JOB_NAME} ${env.BUILD_NUMBER}>)"
    checkout scm
    this.pom = readMavenPom file: 'pom.xml'
    this.gitRemote = sh(returnStdout: true, script: 'git remote get-url origin|cut -c9-').trim()
}

def mvn(params) {
    docker.image(this.mavenDockerImage)
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
                "-Ddocker.pull.registry=${pullRegistry} " +
                "-Ddocker.push.registry=${privateRegistry} " +
                "-Ddocker.buildArg.http_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                "-Ddocker.buildArg.https_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                "-Ddocker.buildArg.no_proxy=hub-docker.lsis.univ-tln.fr,.univ-tln.fr " +
                params
    }
}

def mvnPackage() {
    stage('Package') {
        mvn("package")
        slackSend channel: slackChannel,
                color: "good",
                message: "[<${env.BUILD_URL}|${this.pom.groupId}-${this.pom.artifactId}:${this.pom.version}>] builded."
    }
}

def mvnDeploy() {
    stage('Deploy') {
        mvn("deploy")
        slackSend channel: this.slackChannel,
                color: "good",
                message: "[<${env.BUILD_URL}|${pom.groupId}-${pom.artifactId}:${pom.version}>] pushed."
    }
}

def gitTag() {
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