//configuration
properties([[$class: 'jenkins.model.BuildDiscarderProperty', strategy:
			[$class: 'LogRotator', numToKeepStr: '100', artifactNumToKeepStr: '20']
			]])
			
node {
	def app
    def project = 'trusty-drive-228822'
	def appName = 'aion'
    def imageTag = "gcr.io/${project}/${appName}"

    stage('Clone repository') {
        /* Let's make sure we have the repository cloned to our workspace */
        checkout scm
    }

	stage('Build') {
		// Build steps
		sh "git submodule init" 

		sh "git submodule update --init --recursive"

		sh "./gradlew build pack"	
	}

	stage('Create Image') {
		// Copy to k8s folder to create image
		sh "cp pack/aion.tar.bz2 k8s/aion.tar.bz2"

		app = docker.build("gcr.io/${project}/${appName}")

	}

	stage('Cleanup') {
		//Clean up duplicate files required during the build process
		sh "rm k8s/aion.tar.bz2"
	}

	/*
	stage('Archive build output') {
		when {
			expression { GIT_BRANCH == 'master' || GIT_BRANCH == 'dev' || GIT_BRANCH == 'ci' || GIT_BRANCH == 'dev-audit' }
		}
		steps {                
			archiveArtifacts artifacts: 'pack/aion-v*.tar.bz2'
		}
	}
	*/
	
	// stage('Test') {
	// steps {
	// 		timeout(60){
	// 			sh "./gradlew ciBuild"
	// 		}
	// 	}
	// 	post {
	//         	always {
	//             		junit "report/**/*.xml"
	//         	}
	//     	}
	// }




    post {
		always {
				cleanWs()
		}
    }
    
}
