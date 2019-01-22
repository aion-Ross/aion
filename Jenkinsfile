//configuration
properties([[$class: 'jenkins.model.BuildDiscarderProperty', strategy:
			[$class: 'LogRotator', numToKeepStr: '100', artifactNumToKeepStr: '20']
			]])
			
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                
				// Build steps
                sh "git submodule init" 

                sh "git submodule update --init --recursive"

                sh "./gradlew build pack"

				// Copy to k8s folder to create image
				sh "cp pack/aion.tar.bz2 k8s/aion.tar.bz2"
            }
            
        }

		stage('Package') {

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



    }
    post {
		always {
				cleanWs()
		}
    }
    
}
