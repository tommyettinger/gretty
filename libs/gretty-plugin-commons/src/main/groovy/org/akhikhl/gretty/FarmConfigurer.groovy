/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 *
 * @author akhikhl
 */
class FarmConfigurer {

  private static final Logger log = LoggerFactory.getLogger(FarmConfigurer)

  private final Project project

  FarmConfigurer(Project project) {
    this.project = project
  }

  void configureFarm(Farm dstFarm, Farm[] srcFarms = []) {
    ConfigUtils.complementProperties(dstFarm.serverConfig, srcFarms*.serverConfig + [ ServerConfig.getDefault(project) ])
    dstFarm.serverConfig.resolve(project)
    for(def f in srcFarms)
      mergeWebAppRefMaps(dstFarm.webAppRefs, f.webAppRefs)
    if(!dstFarm.webAppRefs)
      dstFarm.webAppRefs = getDefaultWebAppRefMap()
    if(dstFarm.integrationTestTask == null) dstFarm.integrationTestTask = srcFarms.findResult { it.integrationTestTask }
  }

  Map getDefaultWebAppRefMap() {
    Map result = [:]
    project.subprojects.findAll { it.extensions.findByName('gretty') }.each { p ->
      result[p.path] = [:]
    }
    result
  }

  Farm getProjectFarm(String sourceFarmName) {
    def sourceFarm = project.farms.farmsMap[sourceFarmName]
    if(!sourceFarm)
      throw new GradleException("Farm '${sourceFarmName}' is not defined in project farms")
    sourceFarm
  }

  WebAppConfig getWebAppConfigForMavenDependency(Map options, String dependency) {
    WebAppConfig webappConfig = new WebAppConfig()
    ConfigUtils.complementProperties(webappConfig, options, WebAppConfig.getDefaultForMavenDependency(project, dependency))
    webappConfig.inplace = false // always war-file, ignore options.inplace
    webappConfig.resolve(null)
    webappConfig
  }

  WebAppConfig getWebAppConfigForProject(Map options, Project proj, Boolean inplace = null) {
    WebAppConfig webappConfig = new WebAppConfig()
    if(!proj.extensions.findByName('gretty'))
      throw new GradleException("${proj} does not contain gretty extension. Please make sure that gretty plugin is applied to it.")
    if(proj.ext.grettyPluginJettyVersion != project.ext.grettyFarmPluginJettyVersion)
      throw new GradleException("${proj} uses jetty version ${proj.ext.grettyPluginJettyVersion} different from version ${project.ext.grettyFarmPluginJettyVersion} used by farm.")
    ConfigUtils.complementProperties(webappConfig, options, proj.gretty.webAppConfig, WebAppConfig.getDefaultForProject(proj))
    webappConfig.resolve(proj)
    if(webappConfig.inplace == null) webappConfig.inplace = inplace
    webappConfig
  }

  WebAppConfig getWebAppConfigForWarFile(Map options, File warFile) {
    WebAppConfig webappConfig = new WebAppConfig()
    ConfigUtils.complementProperties(webappConfig, options, WebAppConfig.getDefaultForWarFile(project, warFile))
    webappConfig.inplace = false // always war-file, ignore options.inplace
    webappConfig.resolve(null)
    webappConfig
  }

  Iterable<WebAppConfig> getWebAppConfigsForProjects(Map webAppRefs, Boolean inplace = null) {
    webAppRefs.findResults { webAppRef ->
      def proj = resolveWebAppRefToProject(webAppRef)
      proj ? getWebAppConfigForProject(proj, inplace) : null
    }
  }

  static void mergeWebAppRefMaps(Map dst, Map src) {
    src.each { webAppRef, options ->
      def existingOptions = dst[webAppRef]
      if(existingOptions == null)
        existingOptions = dst[webAppRef] = [:]
      existingOptions << options
    }
  }

  // attention: this method may modify project configurations and dependencies.
  void resolveWebAppRefs(Map sourceWebAppRefs, Collection<WebAppConfig> destWebAppConfigs, Boolean inplace = null) {
    sourceWebAppRefs.each { webAppRef, options ->
      def proj = resolveWebAppRefToProject(webAppRef)
      def warFile
      if(!proj) {
        warFile = resolveWebAppRefToWarFile(webAppRef)
        if(!warFile) {
          webAppRef = webAppRef.toString()
          def gav = webAppRef.split(':')
          if(gav.length != 3)
            throw new GradleException("'${webAppRef}' is not an existing project or file or maven dependency.")
          log.warn '{} is not an existing project or war-file, treating it as a maven dependency', webAppRef
          if(!options.suppressMavenToProjectResolution) {
            proj = project.rootProject.allprojects.find { it.group == gav[0] && it.name == gav[1] }
            if(proj)
              log.warn '{} comes from project {}, so using project instead of maven dependency', webAppRef, proj.path
          }
        }
      }
      WebAppConfig webappConfig
      if(proj)
        webappConfig = getWebAppConfigForProject(options, proj, inplace)
      else if (warFile)
        webappConfig = getWebAppConfigForWarFile(options, warFile)
      else {
        project.configurations.maybeCreate('farm')
        project.dependencies.add 'farm', webAppRef
        webappConfig = getWebAppConfigForMavenDependency(options, webAppRef)
      }
      destWebAppConfigs.add(webappConfig)
    }
  }

  Project resolveWebAppRefToProject(webAppRef) {
    def proj
    if(webAppRef instanceof Project)
      proj = webAppRef
    else if(webAppRef instanceof String || webAppRef instanceof GString)
      proj = project.findProject(webAppRef)
    proj
  }

  File resolveWebAppRefToWarFile(webAppRef) {
    File warFile = webAppRef instanceof File ? webAppRef : new File(webAppRef.toString())
    if(!warFile.isFile() && !warFile.isAbsolute())
      warFile = new File(project.projectDir, warFile.path)
    warFile.isFile() ? warFile.absoluteFile : null
  }
}
