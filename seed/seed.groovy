import utilities.helpers

def jobDescription = 'fake Backend (Core, Api, tools)'
//Branch which publish artifacts and create tags
def primaryBranch = 'master'
//Job that will be called on promotion
def promoteJobName = "deploy-fake-be"
//Environments for promotion
def promoteEnvironments = [["name":"Development","icon":"star-red","env":"dev","srv":"drv"],["name":"Quality Assurance","icon":"star-blue","env":"qa","srv":"drv"]]
//Mail destinations
def mailRecipient = 'testingmyjenkins@gmail.com'
//Temporal var
def shelltmp = ''
//Create folder
folder('jobs')

//Create and define jobs steps
def mainJob = multiJob("${SEED_PROJECT}-${SEED_BRANCH}-build") {
    description "$jobDescription"
    logRotator(-1, 40)
    wrappers {
        environmentVariables {
            groovy("""def build = Thread.currentThread().executable
def version = new File(build.workspace.toString()+"/core/VERSION").text
def ReleaseName = ""
if ( "$SEED_BRANCH" == "master" ) {
    ReleaseName = "\${BUILD_ID}"
} else if ( "$SEED_BRANCH".startsWith("release") ) {
    ReleaseName = "\${BUILD_ID}rc"
} else {
    ReleaseName = "\${BUILD_ID}-${SEED_BRANCH}"
}
def commitsha = ['/bin/bash', '-c', 'cd '+ build.workspace.toString() +'&& git rev-parse --short HEAD'].execute().text
def finalReleaseName = ReleaseName.replaceAll("-","_") + "_" + commitsha
new File("/tmp/${SEED_PROJECT}-${SEED_BRANCH}-parameters").write("VERSION_MASTER=\${version}BUILD_ID_MASTER=\$BUILD_ID\\nFINAL_BUILD_ID=\${finalReleaseName}")
return [VERSION: version.trim(), FINAL_BUILD_ID: finalReleaseName, VERSION_MASTER: version.trim()]""")
        }
    }
    configure { project ->
        project / builders / 'com.cloudbees.jenkins.GitHubSetCommitStatusBuilder' {
        }
    }
    steps {
        phase('Package') {
            phaseJob("jobs/package") {
                parameters {
                    propertiesFile("/tmp/${SEED_PROJECT}-${SEED_BRANCH}-parameters", true)
                    killPhaseCondition('NEVER')
                    gitRevision()
                }
            }
        }

        phase('Create Env') {
            phaseJob("jobs/docker-env"){
                parameters {
                    propertiesFile("/tmp/${SEED_PROJECT}-${SEED_BRANCH}-parameters", true)
                    gitRevision()
                }
            }
        }
        if ( BRANCH.startsWith("release") || BRANCH == "master" || BRANCH == "develop" ) {
            phase('PerformanceTests') {
                phaseJob("jobs/release"){
                    parameters {
                        propertiesFile("/tmp/${SEED_PROJECT}-${SEED_BRANCH}-parameters", true)
                        gitRevision()
                    }
                }
            }
        }
        if (BRANCH == primaryBranch) {
            phase('Publish') {
                phaseJob("jobs/publish"){
                    parameters {
                        propertiesFile("/tmp/${SEED_PROJECT}-${SEED_BRANCH}-parameters", true)
                        killPhaseCondition('NEVER')
                    }
                }

            }
        }
    }
}


helpers.addScm(mainJob,PROJECT_SCM_URL,PROJECT_SCM_CREDENTIALS,BRANCH)

helpers.setBuildName(mainJob, '${ENV,var="VERSION"}-${ENV,var="FINAL_BUILD_ID"}')
helpers.addPromotions(mainJob,promoteEnvironments,promoteJobName)
helpers.addDockerComposeClean(mainJob)
helpers.addPerformenceReport(mainJob)
helpers.addJunitReport(mainJob)
//if primary branch  create git tag and send mail
if (BRANCH == primaryBranch) {
    helpers.addCreateTag(mainJob,"\${VERSION}-\${FINAL_BUILD_ID}")
    helpers.addExtendenMailAlways(mainJob,mailRecipient,jobDescription,'${VERSION}-${FINAL_BUILD_ID}')
} else {
    helpers.addExtendenMailFirstFailure(mainJob,mailRecipient,jobDescription,'${VERSION}-${FINAL_BUILD_ID}')
}

//Child job for jobs/docker-env
def dockerEnvJob = freeStyleJob("jobs/docker-env") {
    description "$jobDescription (child create Docker Environment)"
}
helpers.addScm(dockerEnvJob,PROJECT_SCM_URL,PROJECT_SCM_CREDENTIALS,BRANCH)
helpers.addSkelChild(dockerEnvJob,'${ENV,var="VERSION_MASTER"}-${ENV,var="FINAL_BUILD_ID"}')
shelltmp = """cd \$WORKSPACE
fake_VERSION=\${VERSION_MASTER}-\${FINAL_BUILD_ID} echo 'docker-compose -p \${VERSION_MASTER}-\${FINAL_BUILD_ID} up -d'"""
helpers.addShell(dockerEnvJob,shelltmp)
shelltmp = """sleep 5
COMPOSE_PROJECT=`echo \${VERSION_MASTER}\${FINAL_BUILD_ID} | sed 's/\\.//g;s/_//g;s/-//g' | tr "[:upper:]" "[:lower:]"`
"""
helpers.addShell(dockerEnvJob,shelltmp)


//Child job for core performance test
def performanceTestJob = freeStyleJob("jobs/core-performancetest") {
    description "$jobDescription (child core performancetest)"
}
helpers.addScm(performanceTestJob,PROJECT_SCM_URL,PROJECT_SCM_CREDENTIALS,BRANCH)
helpers.addSkelChild(performanceTestJob,'${ENV,var="VERSION_MASTER"}-${ENV,var="FINAL_BUILD_ID"}', true)
shelltmp = """COMPOSE_PROJECT=`echo \${VERSION_MASTER}\${FINAL_BUILD_ID} | sed 's/\\.//g;s/_//g;s/-//g'| tr "[:upper:]" "[:lower:]"`
fake_VERSION=\${VERSION_MASTER}-\${FINAL_BUILD_ID}  docker-compose -p \${COMPOSE_PROJECT} scale fakecore=3
cd \$WORKSPACE
mkdir -p reports"""
helpers.addShell(performanceTestJob,shelltmp)
helpers.addArchiveArtifacts(performanceTestJob,"reports/coreperformance.xml")
if ( BRANCH.startsWith("release") || BRANCH == "master" || BRANCH == "develop" ) {
    helpers.addCopyArtifacts(mainJob,"core-performancetest","reports/*.xml")
}


//Child job for package api
def packageJob = freeStyleJob("jobs/package") {
    description "$jobDescription (child package api)"
}
helpers.addScm(packageJob,PROJECT_SCM_URL,PROJECT_SCM_CREDENTIALS,BRANCH)
helpers.addSkelChild(packageJob,'${ENV,var="VERSION_MASTER"}-${ENV,var="FINAL_BUILD_ID"}')
shelltmp = """cd \$WORKSPACE/api
RELEASE=\${FINAL_BUILD_ID} VERSION=\${VERSION_MASTER} make clean dockerize"""
helpers.addShell(packageJob,shelltmp)

if (BRANCH == primaryBranch) {
    //Child job for publish tools artefacts
    def publish = freeStyleJob("jobs/publish") {
        description "$jobDescription (child publish tools)"
    }
    helpers.addSkelChild(publish,'${ENV,var="VERSION_MASTER"}-${ENV,var="FINAL_BUILD_ID"}')
    shelltmp = """echo -e \"\n\n\nPublishing \${VERSION_MASTER}-\${FINAL_BUILD_ID} Version"""
    helpers.addShell(publish,shelltmp)
}
