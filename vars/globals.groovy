import groovy.transform.Field

def tokens = "${env.JOB_NAME}".tokenize('/')

@Field
String ORG = tokens[tokens.size() - 3]

@Field
String REPO = tokens[tokens.size() - 2]

@Field
String BRANCH = tokens[tokens.size() - 1]

@Field
String slack_channel
