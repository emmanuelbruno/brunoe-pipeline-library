#!groovy
import groovy.transform.Field

@Field
        tokens = "${env.JOB_NAME}".tokenize('/')

@Field
String ORG = tokens[tokens.size() - 3]

@Field
String REPO = tokens[tokens.size() - 2]

@Field
String BRANCH = tokens[tokens.size() - 1]

@Field
String slackChannel

@Field
String gitRemote

@Field
String pom

@Field
String mavenDockerImage

@Field
String finalMessage

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
    this.getRemote = gitRemote
}

def setFinalMessage(finalMessage) {
    this.finalMessage = finalMessage
}

def appendFinalMessage(message) {
    this.finalMessage += message
}

def mvn(params) {
    docker.image(this.mavenDockerImage)
            .inside(
            "-e http_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                    "-e https_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                    "-e no_proxy=.univ-tln.fr,127.0.0.1,172.18.0.1 " +
                    '-e DOCKER_HOST=unix:///var/run/docker.sock ' +
                    '-v /var/run/docker.sock:/var/run/docker.sock ' +
                    '-v /home/jenkins/.m2/repository:/home/user/.m2/repository ' +
                    '-v /home/jenkins/.sonar:/home/user/.sonar ' +
                    '-v /home/jenkins/.docker:/home/user/.docker ') {

        withCredentials([[$class: 'FileBinding', credentialsId: 'settings-security.xml', variable: 'MAVEN_SETTINGS_SECURITY'],
                         [$class: 'FileBinding', credentialsId: 'settings.xml', variable: 'MAVEN_SETTINGS']
        ]) {
            ansiColor('gnome-terminal') {
                sh "mvn --settings ${MAVEN_SETTINGS} " +
                        "-Dsettings.security=${MAVEN_SETTINGS_SECURITY} " +
                        "-Duser.home=/home/user " +
                        "-B " +
                        "-Ddocker.host=unix:///var/run/docker.sock " +
                        "-Ddocker.buildArg.http_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                        "-Ddocker.buildArg.https_proxy=http://${UTLN_USERNAME}:${UTLN_PASSWORD}@proxy.univ-tln.fr:3128 " +
                        "-Ddocker.buildArg.no_proxy=hub-docker.lsis.univ-tln.fr,.univ-tln.fr " +
                        params
            }
        }
    }
}

def mvnDeploy(params, destination) {
    stage('Deploy') {
        mvn(params + " deploy")
        appendFinalMessage(", deployed to \" + destination + \".\"")
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

//      checkout scm
        checkout([
                $class           : 'GitSCM',
                branches         : scm.branches,
                extensions       : scm.extensions + [[$class: 'CleanCheckout'] + [$class: 'LocalBranch']],
                userRemoteConfigs: scm.userRemoteConfigs
        ])

        this.gitRemote = sh(returnStdout: true, script: 'git remote get-url origin|cut -c9-').trim()

        //Adds an explicit builnumber except for final release and hotfixes
        if (!(BRANCH.equals("master") || BRANCH.startsWith("hotfix-"))) {
            mvn("-DbuildNumber=${env.BUILD_NUMBER} jgitflow:build-number")
        }

        pom = readMavenPom file: 'pom.xml'
        appendFinalMessage("<${env.BUILD_URL}|${pom.groupId}-${pom.artifactId}:${pom.version}>")
    }
}


def mvnBuild() {
    stage('Build') {
        mvn("-P stage-devel -Dmaven.test.skip=true clean package")
        appendFinalMessage(" builded")
    }
}

def mvnTest() {
    stage('Test') {
        mvn("-P stage-devel org.jacoco:jacoco-maven-plugin:prepare-agent " +
                "verify"
        )
        appendFinalMessage(", tested")
    }
}

def mvnQuality() {
    stage('Quality') {
        mvn("-P stage-devel sonar:sonar")
        appendFinalMessage(", <https://sonar.lsis.univ-tln.fr/|qualified>")
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
       git tag jenkins-${pom.artifactId}-${pom.version}
       git push https://${env.GIT_USERNAME}:${env.GIT_PASSWORD}@${gitRemote}  --tags
       """
        }
    }
}

def defaultMavenFullPipeLine(maven_docker_image) {
    node() {
        try {
            //In jenkins add settings.xml, settings-security.xml, login.utln (utln password)
            //This file should be protected (signed ?)

            setSlackChannel("ci")
            //mavenDockerImage = 'hub-docker.lsis.univ-tln.fr:443/brunoe/maven:3-3.9-SNAPSHOT'
            setMavenDockerImage(maven_docker_image)

            //checkout and set version with buildnumber
            init()

            //clean build package without tests
            mvnBuild()
            //run all tests
            mvnTest()
            //check quality
            mvnQuality()

            //Deploy depending on the branch type
            if (BRANCH.equals("master")) {
                mvnDeploy("-P stage-production", "production")
                mvn("site -P github-site")
            } else {
                //gitTag()
                if (BRANCH.equals("development") || BRANCH.startsWith("feature-"))
                    mvnDeploy("-P stage-devel", "devel")
                else if (BRANCH.startsWith("release-") || BRANCH.startsWith("hotfix-"))
                    mvnDeploy("-P stage-staging", "staging")
            }

            slackSend channel: this.slackChannel,
                    color: "good",
                    message: finalMessage

        } catch (error) {
            slackSend channel: slackChannel,
                    color: "danger",
                    message: "Build Error. <${env.BUILD_URL}|${env.JOB_NAME} ${env.BUILD_NUMBER}>) : ${error}"
            throw error
        } finally {
        }
    }
}