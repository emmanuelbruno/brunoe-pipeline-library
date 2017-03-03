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
        slackSend channel: this.slackChannel,
                color: "good",
                message: "[<${env.BUILD_URL}|${pom.groupId}-${pom.artifactId}:${pom.version}>] Deployed to " + destination + "."
    }
}


//FROM : https://issues.jenkins-ci.org/browse/JENKINS-31924
/**
 * Clean a Git project workspace.
 * Uses 'git clean' if there is a repository found.
 * Uses Pipeline 'deleteDir()' function if no .git directory is found.
 */
def gitClean() {
    timeout(time: 60, unit: 'SECONDS') {
        if (fileExists('.git')) {
            echo 'Found Git repository: using Git to clean the tree.'
            // The sequence of reset --hard and clean -fdx first
            // in the root and then using submodule foreach
            // is based on how the Jenkins Git SCM clean before checkout
            // feature works.
            sh 'git reset --hard'
            // Note: -e is necessary to exclude the temp directory
            // .jenkins-XXXXX in the workspace where Pipeline puts the
            // batch file for the 'bat' command.
            sh 'git clean -ffdx -e ".jenkins-*/"'
            sh 'git submodule foreach --recursive git reset --hard'
            sh 'git submodule foreach --recursive git clean -ffdx'
        }
        else
        {
            echo 'No Git repository found: using deleteDir() to wipe clean'
            deleteDir()
        }
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

        gitClean()
        checkout scm
        sh 'git checkout $BRANCH_NAME'
        this.gitRemote = sh(returnStdout: true, script: 'git remote get-url origin|cut -c9-').trim()

        //Adds an explicit builnumber except for final release and hotfixes
        if (BRANCH.equals("master") || BRANCH.startsWith("hotfix-")) {
            mvn("-DbuildNumber=${env.BUILD_NUMBER} jgitflow:build-number")
        }

        pom = readMavenPom file: 'pom.xml'
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
                message: "[<${env.BUILD_URL}|${pom.groupId}-${pom.artifactId}:${pom.version}>] builded."
    }
}

def mvnTest() {
    stage('Test') {
        mvn("-P stage-devel org.jacoco:jacoco-maven-plugin:prepare-agent " +
                "verify"
        )
        slackSend channel: slackChannel,
                color: "good",
                message: "[<${env.BUILD_URL}|${pom.groupId}-${pom.artifactId}:${pom.version}>] Tested."
    }
}

def mvnQuality() {
    stage('Quality') {
        mvn("-P stage-devel sonar:sonar")
        slackSend channel: slackChannel,
                color: "good",
                message: "[<${env.BUILD_URL}|${pom.groupId}-${pom.artifactId}:${pom.version}>] Quality measured."
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
            if (BRANCH.equals("master") || BRANCH.startsWith("hotfix-"))
                mvnDeploy("-P stage-production", "production")
            else {
                //gitTag()
                if (BRANCH.equals("development") || BRANCH.startsWith("feature-"))
                    mvnDeploy("-P stage-devel", "devel")
                else if (BRANCH.startsWith("release-"))
                    mvnDeploy("-P stage-staging", "staging")
            }

        } catch (error) {
            slackSend channel: slackChannel,
                    color: "danger",
                    message: "Build Error. <${env.BUILD_URL}|${env.JOB_NAME} ${env.BUILD_NUMBER}>) : ${error}"
            throw error
        } finally {
        }

    }
}