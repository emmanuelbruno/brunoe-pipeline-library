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

def setPom(pom) {
    this.pom = pom
}

def setGitRemote(gitRemote) {
    this.getRemore = gitRemote
}

def mvn(params) {
    docker.image(this.mavenDockerImage)
            .inside(
            "-e http_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                    "-e https_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                    '-e DOCKER_HOST=tcp://172.18.0.1:2375 ' +
                    '-v /var/run/docker.sock:/var/run/docker.sock ' +
                    '-v /home/jenkins/.m2/repository:/home/user/.m2/repository ' +
                    '-v /home/jenkins/.docker:/home/user/.docker ') {

        withCredentials([[$class: 'FileBinding', credentialsId: 'settings-security.xml', variable: 'MAVEN_SETTINGS_SECURITY'],
                         [$class: 'FileBinding', credentialsId: 'settings.xml', variable: 'MAVEN_SETTINGS']
        ]) {
            ansiColor('gnome-terminal') {
                sh "cp ${env.MAVEN_SETTINGS_SECURITY} /home/user/settings-security.xml"
                sh "mvn --settings ${MAVEN_SETTINGS} " +
                        "-Duser.home=/home/user " +
                        "-B " +
                        "-Ddocker.host=tcp://172.18.0.1:2375 " +
                        "-Ddocker.buildArg.http_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                        "-Ddocker.buildArg.https_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                        "-Ddocker.buildArg.no_proxy=hub-docker.lsis.univ-tln.fr,.univ-tln.fr " +
                        params
            }
        }
    }
}

def mvnDeploy(params) {
    stage('Deploy') {
        mvn(params + " deploy")
        slackSend channel: this.slackChannel,
                color: "good",
                message: "[<${env.BUILD_URL}|${pom.groupId}-${pom.artifactId}:${pom.version}>] pushed."
    }
}

def init() {
    stage('Init') {
        withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                          credentialsId   : 'login.utln',
                          usernameVariable: 'UTLN_USERNAME',
                          passwordVariable: 'UTLN_PASSWORD']]) {
            this.UTLN_USERNAME = env.UTLN_USERNAME
            this.UTLN_PASSWORD = env.UTLN_PASSWORD
        }

        checkout scm
        this.gitRemote = sh(returnStdout: true, script: 'git remote get-url origin|cut -c9-').trim()
        this.pom = readMavenPom file: 'pom.xml'
        mvn("versions:set -DgenerateBackupPoms=false -DnewVersion=" +
                "${pom.version.replaceAll('-SNAPSHOT', '-' + env.BUILD_NUMBER)}")
        this.pom = readMavenPom file: 'pom.xml'
        slackSend channel: this.slackChannel,
                color: "good",
                message: "[<${env.BUILD_URL}|${pom.groupId}-${pom.artifactId}:${pom.version}>] Build starting"
    }
}


def mvnBuild() {
    stage('Build') {
        mvn("-P stage-devel -Dmaven.test.skip=true clean package")
        slackSend channel: slackChannel,
                color: "good",
                message: "[<${env.BUILD_URL}|${this.pom.groupId}-${this.pom.artifactId}:${this.pom.version}>] builded."
    }
}

def mvnTest() {
    stage('Test') {
        mvn("-P stage-devel org.jacoco:jacoco-maven-plugin:prepare-agent " +
                "verify"
        )
        slackSend channel: slackChannel,
                color: "good",
                message: "[<${env.BUILD_URL}|${this.pom.groupId}-${this.pom.artifactId}:${this.pom.version}>] Tested."
    }
}

def mvnQuality() {
    stage('Quality') {
        mvn("-P stage-devel sonar:sonar")
        slackSend channel: slackChannel,
                color: "good",
                message: "[<${env.BUILD_URL}|${this.pom.groupId}-${this.pom.artifactId}:${this.pom.version}>] Tested."
    }
}

def gitTag() {
    stage('Git Tag') {
        withCredentials([[$class          : 'UsernamePasswordMultiBinding',
                          credentialsId   : 'login.github',
                          usernameVariable: 'GIT_USERNAME',
                          passwordVariable: 'GIT_PASSWORD']]) {
            sh """
       git add .
       git commit -m 'Tag Jenkins Build'
       git tag jenkins-${pom.artifactId}-${pom.version}-${env.BUILD_NUMBER}
       git push https://${env.GIT_USERNAME}:${env.GIT_PASSWORD}@${gitRemote}  --tags
       """
        }
    }
}

def defaultMavenFullPipeLine() {
    node() {
        try {
            //In jenkins add settings.xml, settings-security.xml, login.utln (utln password)
            //This file should be protected (signed ?)

            slackChannel = "ci"
            mavenDockerImage = 'hub-docker.lsis.univ-tln.fr:443/brunoe/maven:3-3.9-SNAPSHOT'

            //checkout and set version with buildnumber
            init()

            //clean build package without tests
            mvnBuild()
            //run all tests
            mvnTest()
            mvnDeploy("-P stage-devel")
            //check quality
            mvnQuality()
            mvnDeploy("-P stage-staging")
            mvnDeploy("-P stage-production")

            gitTag()

        } catch (error) {
            slackSend channel: slackChannel,
                    color: "danger",
                    message: "Build Error. <${env.BUILD_URL}|${env.JOB_NAME} ${env.BUILD_NUMBER}>) : ${error}"
            throw error
        } finally {
        }

    }
}