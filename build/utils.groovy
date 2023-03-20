// taken from: https://code.devsnc.com/dev/nagini/blob/master/ci-common/utils.groovy
// Common utilities for the Nagini Jenkinsfiles
//
// To call one of the utility methods, ensure that sources have been checked
// out from Git, then load this module and call the method on the returned module.
//
//    def ciCommonUtils = load 'build/utils.groovy'
//    ciCommonUtils.dockerCleanup()

// Cleanup Docker data
def dockerCleanup() {
    sh label:  'Show docker disk usage',
       script: 'docker system df --verbose'

    sh label:  'Stop and remove all Docker containers',
       script: 'docker container ls -aq | while read id; do docker container stop $id; docker container rm $id; done'

    sh label:  'Remove all networks',
       script: 'docker network prune --force'

    sh label:  'Remove dangling images and build cache created more than 24 hours ago',
       script: 'docker system prune --force --all --filter "until=24h"'

    sh label:  'Show docker disk usage',
       script: 'docker system df --verbose'
}

// Return a module containing the methods defined in this file.
// Required for `load` to work.
return this
