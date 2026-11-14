/*
 * Build of the plugin on ci.jenkins.io, once hosted in the jenkinsci organization.
 * See https://github.com/jenkins-infra/pipeline-library for the options of buildPlugin.
 */
buildPlugin(
  forkCount: '1C',
  useContainerAgent: true,
  configurations: [
    [platform: 'linux', jdk: 21],
    [platform: 'linux', jdk: 25],
    [platform: 'windows', jdk: 21],
  ])
