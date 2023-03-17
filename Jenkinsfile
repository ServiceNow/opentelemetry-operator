// based on https://code.devsnc.com/dev/nagini/blob/master/python/trainer/Jenkinsfile#L256
@Library(['sncPipelineLib']) _
import com.snc.jenkins.GitBranchToVersion
import groovy.json.*

final String PUBLISH_IMAGE_CHOICE_DEFAULT = 'Default branch behavior (see description)'
final String PUBLISH_IMAGE_CHOICE_FALSE = 'False'
final String PUBLISH_IMAGE_CHOICE_TRUE = 'True'

final String BUILD_TYPE_SNAPSHOT = 'Snapshot'
final String BUILD_TYPE_RELEASE_CANDIDATE = 'Release Candidate'
final String BUILD_TYPE_RELEASE = 'Release'
final String BUILD_TYPE_DEVELOPER = 'Dev Testing'



//for a list of all availble env vars: https://build.devsnc.com/teams-star/pipeline-syntax/globals#env
//docs: https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#using-environment-variables
def opts = [
        agentLabel                 : env.SNC_DIND_NODE ?: 'snc-dind',
        container                  : env.SNC_DIND_CONTAINER ?: 'dind',
        registryRelease            : env.REGISTRY_RELEASE ?: 'registry-releases.devsnc.com',
        registrySnapshot           : env.REGISTRY_SNAPSHOT ?: 'registry-snapshots.devsnc.com',
        codeScanPlugin             : 'com.snc.glide.test:snc-code-analysis-plugin:1.scanfrmwk.0-SNAPSHOT:code-scan',
        sncCodeAnalysisRepoBranch  : 'master',
        buildPropertiesFileName    : 'build/build.properties',
        versionPostFix             : '',
        defaultBranchName          : 'master',
        developmentImageNamePostFix: '-snapshot',
        integrationBranchPatterns  : ['master', 'integration/'], // list of branch patterns that publish images by default
        releaseBranchPatterns      : ['release/'], // list of branch patterns that publish images by default and that can publish images to releases
        nightlyBranchPatterns      : [],
        releaseTagsPatterns        : ['release/'],
]

def imageProperties = [
        imageShortName      : 'opentelemetry-operator',
        imageName           : '',
        version             : '',
        versionCore         : '',
        versionPreRelease   : '',
        versionBuildMetadata: '',
        buildMetadataType   : BUILD_TYPE_SNAPSHOT,
        imageTags           : [],
        scanImageTag        : '',
        registry            : opts.registrySnapshot,
        pushImage           : false,
]

def buildProperties


String sanitizedBranchName(String original) {
    return original.replaceAll('/', '_').toLowerCase()
}

pipeline {
    agent { label opts.agentLabel }


    parameters {
        choice(
                name: 'publish_image',
                choices: [PUBLISH_IMAGE_CHOICE_DEFAULT, PUBLISH_IMAGE_CHOICE_FALSE, PUBLISH_IMAGE_CHOICE_TRUE],
                description: 'Publish the image.<br/>' +
                        'Default is false except for master and release branches.'
        )
        choice(
                name: 'build_type',
                choices: [BUILD_TYPE_SNAPSHOT, BUILD_TYPE_RELEASE, BUILD_TYPE_DEVELOPER],
                description: "<b>Snapshot</b> build type produce <code>${imageProperties.imageShortName}-snapshot</code> docker image and publish on the docker registry: "
                        + 'https://nexus.devsnc.com/#browse/browse:snc-docker-snapshots<br/>'
                        + "<b>DeveloperTesting</b> build type produce <code>${imageProperties.imageShortName}-snapshot</code> docker image and publish on the docker registry: "
                        + 'https://nexus.devsnc.com/#browse/browse:snc-docker-snapshots<br/>'
                        + "<b>Release</b> build type produce <code>${imageProperties.imageShortName}</code> docker image and publish on the docker registry: "
                        + 'https://nexus.devsnc.com/#browse/browse:snc-docker-releases<br/>'
                        + "<i><b>WARNING: Release</b> build must be on a release branch: <code>${opts.releaseBranchPatterns}</code></i>"
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '5'))
        disableConcurrentBuilds()
        timeout(time: 2, unit: 'HOURS')
        retry(1)
    }

    stages {
        stage('Cleanup Docker') {
            steps {
                container(opts.container) {
                    script {
                        catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {
                            def ciCommonUtils = load 'build/utils.groovy'
                            ciCommonUtils.dockerCleanup()
                        }
                    }
                }
            }
        }

        stage('Load configuration') {
            steps {
                container('jnlp') {
                    script {
                        env.BUILD_TIMESTAMP = Calendar.instance.time.format('YYYYMMdd-hhmmss', TimeZone.getTimeZone('UTC'))
                        env.EFFECTIVE_BRANCH_NAME = env.BRANCH_NAME ?: scm.branches.first().getExpandedName(env.getEnvironment())


                        String commitShortSHA1 = sh(returnStdout: true, script: "git rev-parse --short ${GIT_COMMIT}").trim()
                        currentBuild.displayName = "#${currentBuild.number}-${commitShortSHA1}"

                        def gitBranch = new GitBranchToVersion(env.EFFECTIVE_BRANCH_NAME,
                                env.CHANGE_ID ?: '',
                                env.TAG_NAME ?: '',
                                opts.releaseBranchPatterns,
                                opts.integrationBranchPatterns,
                                opts.nightlyBranchPatterns,
                                opts.releaseTagsPatterns,
                                opts.versionPostFix)


                        String sanitizedBranchName = sanitizedBranchName(env.EFFECTIVE_BRANCH_NAME)

                        // set the global buildProperties for use in other stages.
                        buildProperties  = readProperties file: opts.buildPropertiesFileName
                        imageProperties.imageName = buildProperties.IMAGE_NAME
                        imageProperties.imageShortName = buildProperties.IMAGE_SHORT_NAME
                        imageProperties.versionCore = buildProperties.IMAGE_VERSION_CORE
                        imageProperties.versionBuildMetadata = "${sanitizedBranchName}.${env.BUILD_NUMBER}.${commitShortSHA1}"
                        imageProperties.buildMetadataType = params.build_type

                        if (!gitBranch.isReleaseBranch()) {
                            imageProperties.imageName = "${imageProperties.imageName}${opts.developmentImageNamePostFix}"
                        }

                        switch (params.build_type) {
                            case BUILD_TYPE_RELEASE:
                                if (!gitBranch.isReleaseBranch()) {
                                    error "Cannot proceed. Release can only occurs from release branches: ${opts.releaseBranchPatterns}"
                                }
                                imageProperties.registry = opts.registryRelease

                                imageProperties.imageTags.add("${imageProperties.versionCore}")
                                imageProperties.imageTags.add("${imageProperties.versionCore}_${imageProperties.versionBuildMetadata}")

                                break
                            case BUILD_TYPE_SNAPSHOT:
                                imageProperties.registry = opts.registrySnapshot

                                if (gitBranch.isReleaseBranch()) {
                                    // each commit on a release branch is a release candidates
                                    imageProperties.versionPreRelease = "rc${env.BUILD_NUMBER}"
                                    imageProperties.buildMetadataType = BUILD_TYPE_RELEASE_CANDIDATE

                                    imageProperties.imageTags.add("${imageProperties.versionCore}-${imageProperties.versionPreRelease}")
                                    imageProperties.imageTags.add("${imageProperties.versionCore}-${imageProperties.versionPreRelease}_${imageProperties.versionBuildMetadata}")

                                    // Use this image tag as to sec-scan container image
                                    // An image with latest tag will be built first before tagging it with release specific tags.
                                    imageProperties.scanImageTag = 'latest'
                                } else {
                                    String shortBuildMetadata = sanitizedBranchName

                                    if (env.EFFECTIVE_BRANCH_NAME == opts.defaultBranchName) {
                                        // default branch owns the latest tag of itom-gateway-snapshot
                                        imageProperties.imageTags.add('latest')

                                        // Automatically updating qe-latest with latest until we provide tools to qe to control it.
                                        imageProperties.imageTags.add('qe-latest')
                                    }

                                    imageProperties.imageTags.add("${imageProperties.versionCore}_${imageProperties.versionBuildMetadata}")
                                    imageProperties.imageTags.add("${imageProperties.versionCore}_${shortBuildMetadata}")

                                    // Use this image tag as to sec-scan container image
                                    imageProperties.scanImageTag = 'latest'
                                }
                                break
                            case BUILD_TYPE_DEVELOPER:
                                imageProperties.registry = opts.registrySnapshot

                                String shortBuildMetadata = sanitizedBranchName
                                imageProperties.imageTags.add('dev-latest')
                                imageProperties.imageTags.add("${imageProperties.versionCore}_${imageProperties.versionBuildMetadata}")
                                imageProperties.imageTags.add("${imageProperties.versionCore}_${shortBuildMetadata}")

                                // Use this image tag as to sec-scan container image
                                imageProperties.scanImageTag = 'dev-latest'
                                break
                            default:
                                error "Unknown build_type: ${params.build_type}"
                        }

                        // determine if the pipeline will push the container image on success
                        switch (params.publish_image) {
                            case PUBLISH_IMAGE_CHOICE_DEFAULT:
                                imageProperties.pushImage = gitBranch.isITBranch() || gitBranch.isReleaseBranch()
                                break
                            case PUBLISH_IMAGE_CHOICE_TRUE:
                                imageProperties.pushImage = true
                                break
                            case PUBLISH_IMAGE_CHOICE_FALSE:
                                imageProperties.pushImage = false
                                break
                            default:
                                error "Unexpected publish_image provided value (${params.publish_image}), cannot determine if image should be pushed."
                        }

                        if (imageProperties.versionPreRelease) {
                            imageProperties.version = "${imageProperties.versionCore}-${imageProperties.versionPreRelease}+${imageProperties.versionBuildMetadata}"
                        } else {
                            imageProperties.version = "${imageProperties.versionCore}+${imageProperties.versionBuildMetadata}"
                        }

                        println new JsonBuilder([
                                'buildConfiguration': [
                                        'params'         : params,
                                        'buildType'      : imageProperties.buildMetadataType,
                                        'pushImage'      : imageProperties.pushImage,
                                        'jenkins'        : [
                                                'jobName'         : "${env.JOB_NAME}",
                                                'jobURL'          : env.JOB_URL,
                                                'buildID'         : env.BUILD_ID,
                                                'buildTimeStamp'  : env.BUILD_TIMESTAMP,
                                                'buildDisplayName': "${env.BUILD_DISPLAY_NAME}",
                                                'workspace'       : "${env.WORKSPACE}",
                                        ],
                                        'scm'            : [
                                                'type'               : 'github',
                                                'baseURL'            : env.GIT_URL_BASE,
                                                'repoURL'            : env.GIT_URL,
                                                'branchName'         : env.EFFECTIVE_BRANCH_NAME,
                                                'sanitizedBranchName': sanitizedBranchName,
                                                'commitSHA1'         : env.GIT_COMMIT,
                                                'commitShortSHA1'    : commitShortSHA1,
                                                'tag'                : env.TAG_NAME ?: 'undefined',
                                                'changeId'           : env.CHANGE_ID ?: 'undefined',
                                                'gitBranchObject'    : gitBranch.toString(),
                                        ],
                                        'imageProperties': imageProperties,
                                        'opts'           : opts,
                                ]
                        ]).toPrettyString()
                    }
                }
            }
        }

        stage('Release Version Check') {
            when {
                equals(
                        expected: BUILD_TYPE_RELEASE,
                        actual: params.build_type
                )
            }
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    script {
                        def userInputVersion =
                                input(
                                        message: "You are about to release '${imageProperties.imageName}' docker image with the version '${imageProperties.versionCore}'.\n" +
                                                'Please re-write the version in the field below to confirm you want to proceed.',
                                        parameters: [string(name: 'version', trim: true)]
                                )

                        println("User input version: ${userInputVersion}\nCode version: ${imageProperties.versionCore}")

                        if (userInputVersion != imageProperties.versionCore) {
                            error "Cannot proceed. Input version ${userInputVersion} does not match the image version core ${imageProperties.versionCore}."
                        }

                        println('Version validation succeeded. Continuing with the release.')
                    }
                }
            }
        }

        stage('Build image') {
            steps {

                //container(name: 'ubuntu', shell: '/bin/sh') {
                //    sh 'printenv | sort ; ruby -v ; go version'
                container(opts.container) {
                    dir("${WORKSPACE}") {
                        withCredentials([
                                usernamePassword(credentialsId: 'jenkins-nexus-user', usernameVariable: 'ARTIFACT_NEXUS_USERNAME', passwordVariable: 'ARTIFACT_NEXUS_PASSWORD')
                        ]) {
                            // Export version information used by the build-release target
                            withEnv([
                                    "VERSION_CORE=${imageProperties.versionCore}",
                                    "VERSION_PRE_RELEASE=${imageProperties.versionPreRelease}",
                                    "VERSION_BUILD_METADATA=${imageProperties.versionBuildMetadata}",
                                    "BUILD_METADATA_TYPE=${imageProperties.buildMetadataType}",
                            ]) {
                                sh label: 'Printing Environment',
                                    script: 'printenv | sort'
                                sh label: 'Build the release container',
                                        script: 'make container'
                            }
                        }
                    }

                    sh 'docker image ls --all --digests'
                }
            }
        }

        stage('Post-Build validations') {
            parallel {
//                 stage('Legal Scans') {
//                     when {
//                         equals(
//                                 expected: BUILD_TYPE_RELEASE,
//                                 actual: params.build_type
//                         )
//                     }
//                     steps {
//                         container(opts.container) {
//                             dir("${WORKSPACE}/") {
//                                 sh label:  'Run the legal scans',
//                                    script: 'make legal-scan'
//                             }
//                         }
//                     }
//                 }

                stage('Security Scans') {
                    steps {
                        // Export scanImageTag
                        withEnv([
                                "SCAN_IMAGE_TAG=${imageProperties.scanImageTag}",
                        ]) {
                            container(opts.container) {
                                dir("${WORKSPACE}/") {
                                    script {
                                        catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {
                                            sh label:  'Running security scans',
                                               script: 'make security-scan'
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } // end of parallel execution for Post-Build validations
        }

        stage('Publish image') {
            when {
                expression { return imageProperties.pushImage }
            }
            steps {
                container(opts.container) {
                    script {
                        sh 'docker image ls --all --digests'

                        println "Preparing to publish the docker images '${imageProperties.registry}/${imageProperties.imageName}' with the following tags:" + new JsonBuilder(imageProperties.imageTags).toPrettyString()

                        imageProperties.imageTags.each {
                            String dockerImage = "${imageProperties.registry}/${imageProperties.imageName}:$it"

                            sh label: "Tagging image ${dockerImage}",
                                    script: "docker tag ${imageProperties.imageShortName} ${dockerImage}"

                            sh label: "Publishing image ${dockerImage}",
                                    script: "docker push ${dockerImage}"
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            // Archiving reports / results etc
            archiveArtifacts allowEmptyArchive: true, artifacts: 'reports/grype/*.csv'

            container(opts.container) {
                script {
                    catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {
                        def ciCommonUtils = load 'build/utils.groovy'
                        ciCommonUtils.dockerCleanup()
                    }
                }
            }

            // This wipes out the source checkout, so should be last.
            cleanWs()
        }
    }
}
