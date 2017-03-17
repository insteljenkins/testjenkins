package utilities
//Version 0.0.4
class helpers {
    static void getVersionFromFile(def job, def versionFile = "/VERSION"){
        job.with {
            wrappers {
                environmentVariables {
                    groovy("""def build = Thread.currentThread().executable
def version = new File(build.workspace.toString()+"$versionFile").text
return [VERSION: version.trim()]""")
                }
            }
        }
    }
    static void getVersionFromDate(def job){
        job.with {
            wrappers {
                environmentVariables {
                    groovy("""import java.util.Date
def date = new Date(System.currentTimeMillis())
return [VERSION: date.format('yyyyMMddHHmmss')]""")
                }
            }
        }
    }
    static void setBuildName(def job, def jobBuildName) {
        job.with {
            wrappers {
                buildName("$jobBuildName")
            }
        }
    }
    static void setConsoleColor(def job) {
        job.with {
            wrappers {
                colorizeOutput()
            }
        }
    }
    static void addPromotions(def job, def promoteEnvironments, def promoteJobName) {
        job.with {
            properties {
                promotions {
                    promoteEnvironments.each { environment ->
                        promotion {
                            name(environment.name)
                            icon(environment.icon)
                            conditions {
                                manual('')
                            }
                            actions {
                                downstreamParameterized {
                                    trigger(promoteJobName,"SUCCESS",false,["buildStepFailure": "FAILURE","failure":"FAILURE","unstable":"UNSTABLE"]) {
                                        predefinedProp("ENVIRONMENT",environment.env)
                                        predefinedProp("PROMOTED_URL",'$PROMOTED_URL')
                                        predefinedProp("SERVICE",environment.srv)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    static void addScm(def job, def scmUrl, def scmCredentials, def scmBranch, def mergeBefore = false){
        job.with {
            scm {
                git {
                    remote {
                        name "origin"
                        url "$scmUrl"
                        credentials "$scmCredentials"
                    }
                    branch("origin/$scmBranch")
                    if (mergeBefore) {
                         mergeOptions('origin', 'master')
                    }
                }
            }
        }
    }
    static void addArtifactoryPublish(def job, def repository, def pattern){
        job.with {
            configure { project ->
                project / buildWrappers << 'org.jfrog.hudson.generic.ArtifactoryGenericConfigurator' {
                    details {
                        artifactoryUrl('http://artifactory.localhost:8081/artifactory')
                        artifactoryName('-406811472@1427745126607')
                        repositoryKey("$repository")
                        snapshotsRepositoryKey("$repository")
                    }
                    deployPattern("$pattern")
                }
            }
        }
    }
    static void addOntrackBuildNotifier(def job, def ontrackProject, def ontrackBranch, def ontrackBuildid){
        job.with {
            configure { node ->
                node / 'publishers' / 'net.nemerosa.ontrack.jenkins.OntrackBuildNotifier' {
                    'project'("$ontrackProject")
                    'branch'("$ontrackBranch")
                    'build'("$ontrackBuildid")
                }
            }
        }
    }
    static void addOntrackValidation(def job, def ontrackProject, def ontrackBranch, def ontrackBuildid, def ontrackValidationStamp){
        job.with {
            configure { node ->
                node / 'publishers' / 'net.nemerosa.ontrack.jenkins.OntrackValidationRunNotifier' {
                    'project'("$ontrackProject")
                    'branch'("$ontrackBranch")
                    'build'("$ontrackBuildid")
                    'validationStamp'("$ontrackValidationStamp")
                }
            }
        }
    }
    static void addDockerComposeClean(def job, def onlyOnFail = false){
        job.with {
            publishers {
                postBuildScripts {
                    steps {
                        shell("""COMPOSE_PROJECT=`echo \${VERSION_MASTER}\${FINAL_BUILD_ID} | sed 's/\\.//g;s/_//g;s/-//g' | tr "[:upper:]" "[:lower:]"`
docker-compose -p \${COMPOSE_PROJECT} stop
docker-compose -p \${COMPOSE_PROJECT} rm -v -f"""
                            )
                    }
                    onlyIfBuildSucceeds(false)
                    if (onlyOnFail) {
                        onlyIfBuildFails()
                    }
                }
            }
        }
    }

    static void addGithubNotifier(def job){
        job.with {
            publishers {
                githubCommitNotifier()
            }
        }
    }
    static void addSlackNotifier(def job, def slackChannel){
        job.with {
            publishers {
                slackNotifications {
                    projectChannel("$slackChannel")
                    notifyBuildStart(false)
                    notifyAborted(true)
                    notifyFailure(true)
                    notifyNotBuilt(true)
                    notifySuccess(true)
                    notifyUnstable(true)
                    notifyBackToNormal(true)
                    notifyRepeatedFailure(false)
                }
            }
        }
    }
    static void addCreateTag(def job, def tagName){
        job.with {
            publishers {
                git {
                    pushOnlyIfSuccess(true)
                    pushMerge(true)
                    forcePush(true)
                    tag("origin", "$tagName") {
                        message('Automatic tag create by CI')
                        create(true)
                        update(false)
                    }
                }
            }
        }
    }
    static void addExtendenMailAlways(def job, def extMailRecipient, def extMailProject, def extMailBuildVersion){
        job.with {
            publishers {
                extendedEmail("$extMailRecipient", "$extMailProject build: \$BUILD_STATUS!", "Generated version: $extMailBuildVersion\n\nCHANGES\n------------\n\${CHANGES_SINCE_LAST_SUCCESS}") {
                    trigger(triggerName: 'Always')
                    configure { node ->
                        node / attachBuildLog << 'true'
                        node / compressBuildLog << 'true'
                    }
                }
            }
        }
    }
    static void addExtendenMailFirstFailure(def job, def extMailRecipient, def extMailProject, def extMailBuildVersion){
        job.with {
            publishers {
                extendedEmail(null, "$extMailProject build: \$BUILD_STATUS!", "Generated version: $extMailBuildVersion\n\nCHANGES\n------------\n\${CHANGES_SINCE_LAST_SUCCESS}") {
                    trigger('FirstFailure',null,null,null,true,true,false,false)
                }
            }
        }
    }
    static void addSkelChild(def job, def jobBuildName, def gitHubStatus = false){
        job.with {
            logRotator(-1, 40)
            parameters {
                stringParam("VERSION_MASTER")
                stringParam("BUILD_ID_MASTER")
                stringParam("FINAL_BUILD_ID")
            }
            wrappers {
                buildName("$jobBuildName")
            }
            configure { node ->
                node / 'properties' / 'hudson.plugins.copyartifact.CopyArtifactPermissionProperty' {
                    projectNameList {
                        string("*")
                    }
                }
            }
            if (gitHubStatus) {
                configure { project ->
                    project / builders / 'com.cloudbees.jenkins.GitHubSetCommitStatusBuilder' {
                    }
                }
                publishers {
                    githubCommitNotifier()
                }
            }
        }
    }
    static void addShell(def job, def cmd){
        job.with {
            steps {
                shell "$cmd"
            }
        }
    }
    static void addArchiveArtifacts(def job, def path){
        job.with {
            publishers {
                archiveArtifacts(path)
            }
        }
    }
    static void addPerformenceReport(def job, def path = "reports/*.xml"){
        job.with {
            configure { node ->
                node / 'publishers' / 'hudson.plugins.performance.PerformancePublisher' {
                    parsers {
                        'hudson.plugins.performance.JUnitParser' {
                            glob(path)
                        }
                    }
                }
            }
        }
    }
    static void addCopyArtifacts(def job, def origin, def path, def target = false){
// BUG: It should be selected by multiJobBuild but ...
        def build = origin.toUpperCase()
        build = build.replaceAll("-","_")
        job.with {
            steps {
                copyArtifacts("jobs/$origin") {
                    includePatterns(path)
                    if (target) {
                        targetDirectory(dest)
                    }
                    buildSelector {
                        buildNumber("\$${build}_BUILD_NUMBER")
//                        multiJobBuild()
                    }
                }
            }
        }
    }
    static void addJunitReport(def job, def path = "reports/*.xml"){
        job.with {
            configure { node ->
                node / 'publishers' / 'hudson.tasks.junit.JUnitResultArchiver' {
                    testResults(path)
                }
            }
        }
    }
}
