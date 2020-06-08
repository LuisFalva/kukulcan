package com.github.mmolimar.kukulcan

import _root_.java.util.Properties

import kafka.admin.AdminOperationException
import kafka.utils.Whitelist
import org.apache.kafka.clients.admin._

import scala.collection.JavaConverters._
import scala.util.Try

class KAdmin(val props: Properties) {

  val servers: String = props.getProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG)
  val client: Admin = Admin.create(props)
  val topics = new KAdminTopics
  val configs = new KAdminConfigs
  val acls = new KAdminAcls
  val metrics = new KAdminMetrics

  class KAdminTopics {

    import kafka.admin.TopicCommand.{PartitionDescription, TopicDescription}
    import kafka.common.AdminCommandFailedException
    import kafka.utils.CoreUtils
    import org.apache.kafka.common.config.ConfigResource
    import org.apache.kafka.common.config.ConfigResource.Type
    import org.apache.kafka.common.errors.{ClusterAuthorizationException, TopicAuthorizationException, TopicExistsException, UnsupportedVersionException}
    import org.apache.kafka.common.internals.Topic
    import org.apache.kafka.common.{TopicPartition, TopicPartitionInfo}

    def createTopic(
                     name: String,
                     partitions: Int,
                     replicationFactor: Short,
                     configs: Map[String, String] = Map.empty,
                     options: Option[CreateTopicsOptions] = None
                   ): Boolean = {
      val topic = new NewTopic(name, partitions, replicationFactor).configs(configs.asJava)
      createTopics(Seq(topic), options)
    }

    def createTopicWithReplicasAssignments(
                                            name: String,
                                            replicasAssignments: Map[Integer, Seq[Integer]],
                                            configs: Map[String, String] = Map.empty,
                                            options: Option[CreateTopicsOptions] = None
                                          ): Boolean = {
      val topic = new NewTopic(name, replicasAssignments.map(r => r._1 -> r._2.asJava).asJava)
        .configs(configs.asJava)
      createTopics(Seq(topic), options)
    }

    def createTopics(newTopics: Seq[NewTopic], options: Option[CreateTopicsOptions] = None): Boolean = {
      val opt = options.getOrElse(new CreateTopicsOptions().validateOnly(false))
      val createdTopics = client.createTopics(newTopics.asJava, opt)
        .values.asScala
        .flatMap { r =>
          val topic = r._1
          Try {
            r._2.get
            println(s"Created topic $topic on brokers at $servers")
            Some(topic)
          }.failed.toOption.flatMap(_.getCause match {
            case _: TopicExistsException =>
              println(s"Found existing topic '$topic' on the brokers at $servers")
              Some(topic)
            case _: UnsupportedVersionException =>
              Console.err.println(s"Unable to create topic(s) '${newTopics.map(_.name).mkString(", ")}' since the " +
                s"brokers at $servers do not support the CreateTopics API. Falling back to assume topic(s) exist or " +
                s"will be auto-created by the broker.")
              None
            case _: ClusterAuthorizationException =>
              Console.err.println(s"Not authorized to create topic(s) '${newTopics.map(_.name).mkString(", ")}' upon " +
                s"the brokers $servers. Falling back to assume topic(s) exist or will be auto-created by the broker.")
              None
            case _: TopicAuthorizationException =>
              Console.err.println(s"Not authorized to create topic(s) '{${newTopics.map(_.name).mkString(", ")}}' " +
                s"upon the brokers $servers. Falling back to assume topic(s) exist or will be auto-created by " +
                s"the broker.")
              None
            case e: Throwable =>
              throw new AdminOperationException(s"Error while attempting to create topic(s) " +
                s"'${newTopics.map(_.name).mkString(", ")}'", e)
          }).filter(_.nonEmpty)
        }.toSet
      newTopics.forall(newTopic => createdTopics.contains(newTopic.name()))
    }

    def getTopics(topicWhitelist: Option[String] = None, excludeInternalTopics: Boolean = false): Seq[String] = {
      val allTopics = (if (excludeInternalTopics) {
        client.listTopics
      } else {
        client.listTopics(new ListTopicsOptions().listInternal(true))
      }).names.get.asScala.toSeq.sorted

      if (topicWhitelist.isDefined) {
        val topicsFilter = Whitelist(topicWhitelist.get)
        allTopics.filter(topicsFilter.isTopicAllowed(_, excludeInternalTopics))
      } else {
        allTopics.filterNot(Topic.isInternal(_) && excludeInternalTopics)
      }
    }

    def listTopics(topicWhitelist: Option[String] = None, excludeInternalTopics: Boolean = false): Unit = {
      val topics = getTopics(topicWhitelist, excludeInternalTopics)
      if (topics.isEmpty) {
        println(s"There are no topics.")
      } else {
        println(s"Topic list: ${topics.mkString(", ")}")
      }
    }

    def getTopicAndPartitionDescription(
                                         topic: String,
                                         options: Option[DescribeTopicsOptions]
                                       ): Seq[(TopicDescription, Seq[PartitionDescription])] = {
      getTopicAndPartitionDescription(Seq(topic), options)
    }

    def getTopicAndPartitionDescription(
                                         topics: Seq[String],
                                         options: Option[DescribeTopicsOptions] = None
                                       ): Seq[(TopicDescription, Seq[PartitionDescription])] = {
      val allConfigs = client.describeConfigs(topics.map(new ConfigResource(Type.TOPIC, _)).asJavaCollection).values
      val opt = options.getOrElse(new DescribeTopicsOptions)
      client.describeTopics(topics.asJava, opt).all.get.values.asScala
        .map { td =>
          val topicName = td.name
          val config = allConfigs.get(new ConfigResource(Type.TOPIC, topicName)).get
          val reassignments = client.listPartitionReassignments(new ListPartitionReassignmentsOptions)
            .reassignments.get.asScala
          val partitions = td.partitions.asScala.sortBy(_.partition)
          val topicDescription = {
            val numPartitions = td.partitions.size
            val firstPartition = td.partitions.iterator.next
            val reassignment = reassignments.get(new TopicPartition(td.name, firstPartition.partition))
            val replicationFactor = getReplicationFactor(firstPartition, reassignment)
            TopicDescription(topicName, numPartitions, replicationFactor, config, markedForDeletion = false)
          }
          val partitionDescription = partitions.map { p =>
            val reassignment = reassignments.get(new TopicPartition(td.name, p.partition))
            PartitionDescription(topicName, p, Some(config), markedForDeletion = false, reassignment)
          }
          (topicDescription, partitionDescription)
        }.toSeq
    }

    def describeTopic(
                       name: String,
                       options: Option[DescribeTopicsOptions] = None,
                       withConfigs: Boolean = true,
                       withPartitions: Boolean = true
                     ): Unit = {
      describeTopics(Seq(name), options, withConfigs, withPartitions)
    }

    def describeTopics(
                        topics: Seq[String],
                        options: Option[DescribeTopicsOptions] = None,
                        withConfigs: Boolean = true,
                        withPartitions: Boolean = true
                      ): Unit = {
      getTopicAndPartitionDescription(topics, options)
        .foreach { descriptions =>
          if (withConfigs) {
            descriptions._1.printDescription()
          }
          if (withPartitions) {
            descriptions._2.foreach(_.printDescription())
          }
        }
    }

    private def getReplicationFactor(tpi: TopicPartitionInfo, reassignment: Option[PartitionReassignment]): Int = {
      def isReassignmentInProgress(ra: PartitionReassignment): Boolean = {
        val allReplicaIds = tpi.replicas.asScala.map(_.id).toSet
        val removingReplicaIds = ra.removingReplicas.asScala.map(Int.unbox).toSet
        allReplicaIds.exists(removingReplicaIds.contains)
      }

      reassignment match {
        case Some(ra) if isReassignmentInProgress(ra) => ra.replicas.asScala.diff(ra.addingReplicas.asScala).size
        case _ => tpi.replicas.size
      }
    }

    def deleteTopic(name: String): Unit = deleteTopics(Seq(name))

    def deleteTopics(topics: Seq[String]): Unit = {
      val availableTopics = getTopics()
      topics.foreach { t =>
        if (availableTopics.contains(t)) {
          client.deleteTopics(Seq(t).asJava).all().get()
          println(s"Topic $t has been removed.")
        } else {
          println(s"Topic $t does not exist.")
        }
      }
    }

    def getNewPartitionsDistribution(
                                      topic: String,
                                      partitions: Int,
                                      replicaAssignment: Map[Int, Seq[Int]]
                                    ): Map[String, NewPartitions] = {
      getNewPartitionsDistribution(Seq(topic), partitions, replicaAssignment)
    }

    def getNewPartitionsDistribution(
                                      topics: Seq[String],
                                      partitions: Int,
                                      replicaAssignment: Map[Int, Seq[Int]] = Map.empty
                                    ): Map[String, NewPartitions] = {
      def validateReplicaAssignment(): Unit = {
        replicaAssignment.values.map(CoreUtils.duplicates(_)).find(_.nonEmpty).foreach(duplicates =>
          throw new AdminCommandFailedException(s"Partition replica lists may not contain duplicate entries: " +
            duplicates.mkString(","))
        )
        replicaAssignment.headOption.foreach { ra =>
          val numBrokers = ra._2.size
          replicaAssignment.find(_._2.size != numBrokers).foreach(invalid =>
            throw new AdminOperationException(s"Partition ${invalid._1} has different replication factor:" +
              invalid._2.mkString(", "))
          )
        }
      }

      val topicsInfo = client.describeTopics(topics.asJava).values
      topics.map { topicName =>
        if (replicaAssignment.nonEmpty) {
          validateReplicaAssignment()
          val partitionId = topicsInfo.get(topicName).get.partitions.size
          val newAssignment = replicaAssignment.drop(partitionId).values.map(_.map(Integer.valueOf).asJava).toSeq.asJava
          topicName -> NewPartitions.increaseTo(partitions, newAssignment)
        } else {
          topicName -> NewPartitions.increaseTo(partitions)
        }
      }.toMap
    }

    def alterTopic(name: String, partitions: Int, replicaAssignment: Map[Int, Seq[Int]] = Map.empty): Unit = {
      alterTopics(Seq(name), partitions, replicaAssignment)
    }

    def alterTopics(topics: Seq[String], partitions: Int, replicaAssignment: Map[Int, Seq[Int]] = Map.empty): Unit = {
      val newPartitions = getNewPartitionsDistribution(topics, partitions, replicaAssignment).asJava
      client.createPartitions(newPartitions).all.get
      topics.foreach(t => println(s"Topic $t now has $partitions partitions."))
    }

  }

  class KAdminConfigs {

    import _root_.java.util.Collections
    import _root_.java.util.concurrent.TimeUnit

    import kafka.admin.ConfigCommand.BrokerLoggerConfigType
    import kafka.server.ConfigType
    import org.apache.kafka.common.config.ConfigResource
    import org.apache.kafka.common.errors.InvalidConfigurationException
    import org.apache.kafka.common.internals.Topic

    import scala.collection.Map

    private def getConfig(
                           entityType: String,
                           entityName: String,
                           includeSynonyms: Boolean,
                           describeAll: Boolean
                         ): Seq[ConfigEntry] = {
      val (configResourceType, dynamicConfigSource) = entityType match {
        case ConfigType.Topic =>
          if (!entityName.isEmpty) {
            Topic.validate(entityName)
          }
          (ConfigResource.Type.TOPIC, Some(ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG))
        case ConfigType.Broker => entityName match {
          case "" => (ConfigResource.Type.BROKER, Some(ConfigEntry.ConfigSource.DYNAMIC_DEFAULT_BROKER_CONFIG))
          case _ => (ConfigResource.Type.BROKER, Some(ConfigEntry.ConfigSource.DYNAMIC_BROKER_CONFIG))
        }
        case BrokerLoggerConfigType => (ConfigResource.Type.BROKER_LOGGER, None)
      }
      val configSourceFilter = if (describeAll) None else dynamicConfigSource
      val configResource = new ConfigResource(configResourceType, entityName)
      val describeOptions = new DescribeConfigsOptions().includeSynonyms(includeSynonyms)
      val configs = client.describeConfigs(Collections.singleton(configResource), describeOptions).all.get
      configs.get(configResource).entries.asScala
        .filter(entry => configSourceFilter match {
          case Some(configSource) => entry.source == configSource
          case None => true
        }).toSeq
    }

    private def getValidatedConfig(
                                    entityType: String,
                                    entityName: String,
                                    configsToBeDeleted: Seq[String],
                                    includeSynonyms: Boolean,
                                    describeAll: Boolean
                                  ): Map[String, ConfigEntry] = {
      val oldConfig = getConfig(entityType, entityName, includeSynonyms = includeSynonyms, describeAll = describeAll)
        .map(entry => (entry.name, entry)).toMap
      val invalidConfigs = configsToBeDeleted.filterNot(oldConfig.contains)
      if (invalidConfigs.nonEmpty) {
        throw new InvalidConfigurationException(s"Invalid config(s): ${invalidConfigs.mkString(",")}")
      }
      oldConfig
    }

    private def printAlterConfigResult(entityType: String, entityName: String): Unit = {
      if (entityName.nonEmpty) {
        println(s"Completed updating config for ${entityType.dropRight(1)} $entityName.")
      } else {
        println(s"Completed updating default config for $entityType in the cluster.")
      }
    }

    def alterTopicConfig(
                          name: String,
                          configsToBeAdded: Map[String, String],
                          configsToBeDeleted: Seq[String]
                        ): Unit = {
      getValidatedConfig(ConfigType.Topic, name, configsToBeDeleted, includeSynonyms = false, describeAll = false)
      val configResource = new ConfigResource(ConfigResource.Type.TOPIC, name)
      val alterOptions = new AlterConfigsOptions().timeoutMs(30000).validateOnly(false)
      val newConfigs = configsToBeAdded.map { case (k, v) => (k, new ConfigEntry(k, v)) }
      val alterEntries = (newConfigs.values.map(new AlterConfigOp(_, AlterConfigOp.OpType.SET))
        ++ configsToBeDeleted.map(k => new AlterConfigOp(new ConfigEntry(k, ""), AlterConfigOp.OpType.DELETE))
        ).asJavaCollection
      client.incrementalAlterConfigs(Map(configResource -> alterEntries).asJava, alterOptions).all
        .get(60, TimeUnit.SECONDS)

      printAlterConfigResult(ConfigType.Topic, name)
    }

    def alterBrokerConfig(
                           brokerId: Option[Int],
                           configsToBeAdded: Map[String, String],
                           configsToBeDeleted: Seq[String]
                         ): Unit = {
      val oldConfig = getValidatedConfig(ConfigType.Broker, brokerId.map(_.toString).getOrElse(""),
        configsToBeDeleted, includeSynonyms = false, describeAll = false)
      val newConfigs = configsToBeAdded.map { case (k, v) => (k, new ConfigEntry(k, v)) }
      val newEntries = oldConfig ++ newConfigs -- configsToBeDeleted
      val sensitiveEntries = newEntries.filter(_._2.value == None.orNull)
      if (sensitiveEntries.nonEmpty) {
        throw new InvalidConfigurationException(s"All sensitive broker config entries must be specified. " +
          s"Missing entries: ${sensitiveEntries.keySet}")
      }

      val configResource = new ConfigResource(ConfigResource.Type.BROKER, brokerId.map(_.toString).getOrElse(""))
      val alterOptions = new AlterConfigsOptions().timeoutMs(30000).validateOnly(false)
      val alterEntries = (
        newConfigs.values.map(new AlterConfigOp(_, AlterConfigOp.OpType.SET)) ++
          configsToBeDeleted.map(k => new AlterConfigOp(new ConfigEntry(k, ""), AlterConfigOp.OpType.DELETE))
        ).asJavaCollection
      client.incrementalAlterConfigs(Map(configResource -> alterEntries).asJava, alterOptions).all
        .get(60, TimeUnit.SECONDS)

      printAlterConfigResult(ConfigType.Broker, brokerId.map(_.toString).getOrElse(""))
    }

    def alterBrokerLoggerConfig(
                                 brokerId: Option[Int],
                                 configsToBeAdded: Map[String, String],
                                 configsToBeDeleted: Seq[String]
                               ): Unit = {
      val validLoggers = getConfig(BrokerLoggerConfigType, brokerId.map(_.toString).getOrElse(""),
        includeSynonyms = true, describeAll = false).map(_.name)
      val invalidBrokerLoggers = configsToBeDeleted.filterNot(validLoggers.contains) ++
        configsToBeAdded.keys.filterNot(validLoggers.contains)
      if (invalidBrokerLoggers.nonEmpty) {
        throw new InvalidConfigurationException(s"Invalid broker logger(s): ${invalidBrokerLoggers.mkString(",")}")
      }

      val newConfigs = configsToBeAdded.map { case (k, v) => (k, new ConfigEntry(k, v)) }
      val configResource = new ConfigResource(ConfigResource.Type.BROKER_LOGGER, brokerId.map(_.toString).getOrElse(""))
      val alterOptions = new AlterConfigsOptions().timeoutMs(30000).validateOnly(false)
      val alterLogLevelEntries = (newConfigs.values.map(new AlterConfigOp(_, AlterConfigOp.OpType.SET))
        ++ configsToBeDeleted.map { k => new AlterConfigOp(new ConfigEntry(k, ""), AlterConfigOp.OpType.DELETE) }
        ).asJavaCollection
      client.incrementalAlterConfigs(Map(configResource -> alterLogLevelEntries).asJava, alterOptions).all
        .get(60, TimeUnit.SECONDS)

      printAlterConfigResult(BrokerLoggerConfigType, brokerId.map(_.toString).getOrElse(""))
    }

    private def getDescribeConfig(entities: Seq[String], entityType: String, describeAll: Boolean): Seq[ConfigEntry] = {
      entities.flatMap(entity => getConfig(entityType, entity, includeSynonyms = true, describeAll))
    }

    def getDescribeTopicConfig(name: Option[String], describeAll: Boolean = true): Seq[ConfigEntry] = {
      getDescribeConfig(name.map(Seq(_)).getOrElse(topics.getTopics()), ConfigType.Topic, describeAll)
    }

    def getDescribeBrokerConfig(brokerId: Option[Int], describeAll: Boolean = true): Seq[ConfigEntry] = {
      val brokerList = brokerId.map(id => Seq(id.toString)).getOrElse {
        client.describeCluster.nodes.get.asScala
          .map(_.idString).toSeq
      }
      getDescribeConfig(brokerList, ConfigType.Broker, describeAll)
    }

    def getDescribeBrokerLoggerConfig(brokerId: Option[Int], describeAll: Boolean = true): Seq[ConfigEntry] = {
      val brokerList = brokerId.map(id => Seq(id.toString)).getOrElse {
        client.describeCluster.nodes.get.asScala
          .map(_.idString).toSeq
      }
      getDescribeConfig(brokerList, BrokerLoggerConfigType, describeAll)
    }

    private def printDescribeConfig(entity: Option[Any], entityType: String, configs: Seq[ConfigEntry], describeAll: Boolean): Unit = {
      val configSourceStr = if (describeAll) "All" else "Dynamic"
      entity match {
        case None =>
          println(s"Default configs for $entityType in the cluster are:")
        case Some(t) =>
          println(s"$configSourceStr configs for ${entityType.dropRight(1)} $t are:")
      }
      configs.foreach { entry =>
        val synonyms = entry.synonyms.asScala.map(synonym => s"${synonym.source}:${synonym.name}=${synonym.value}").mkString(", ")
        println(s"  ${entry.name}=${entry.value} sensitive=${entry.isSensitive} synonyms={$synonyms}")
      }
    }

    def describeTopicConfig(name: Option[String], describeAll: Boolean = true): Unit = {
      val configs = getDescribeTopicConfig(name, describeAll)
      printDescribeConfig(name, ConfigType.Topic, configs, describeAll)
    }

    def describeBrokerConfig(brokerId: Option[Int], describeAll: Boolean = true): Unit = {
      val configs = getDescribeBrokerConfig(brokerId, describeAll)
      printDescribeConfig(brokerId, ConfigType.Broker, configs, describeAll)
    }

    def describeBrokerLoggerConfig(brokerId: Option[Int], describeAll: Boolean = true): Unit = {
      val configs = getDescribeBrokerLoggerConfig(brokerId, describeAll)
      printDescribeConfig(brokerId, BrokerLoggerConfigType, configs, describeAll)
    }

  }

  class KAdminAcls {

    import kafka.security.authorizer.{AclAuthorizer, AuthorizerUtils}
    import org.apache.kafka.common.acl.{AclBinding, AclBindingFilter}
    import org.apache.kafka.common.security.auth.KafkaPrincipal
    import org.apache.kafka.common.utils.Utils
    import org.apache.kafka.server.authorizer.Authorizer

    import scala.compat.java8.OptionConverters._

    def defaultAuthorizer(forceDefault: Boolean): Option[Authorizer] = {
      props.asScala.get("authorizer.class.name").orElse(Some(classOf[AclAuthorizer].getName))
        .map { className =>
          val authorizer = AuthorizerUtils.createAuthorizer(className)
          authorizer.configure(props.asScala.toMap.asJava)
          authorizer
        }
    }

    def addAcls(
                 aclBindings: Seq[AclBinding],
                 options: CreateAclsOptions = new CreateAclsOptions,
                 forceAuthorizer: Boolean = true
               ): Unit = {
      aclBindings.groupBy(_.pattern).foreach { acl =>
        val resource = acl._1
        val entries = acl._2.map(_.entry)
        println(s"Adding ACLs for resource '$resource': \n ${entries.map("\t" + _).mkString("\n")}\n")
        val aclBindings = entries.map(entry => new AclBinding(resource, entry)).asJava
        defaultAuthorizer(forceAuthorizer).map { authorizer =>
          authorizer.createAcls(None.orNull, aclBindings).asScala
            .map(_.toCompletableFuture.get)
            .foreach { result =>
              result.exception.asScala.foreach { exception =>
                Console.err.println(s"Error while adding ACLs: ${exception.getMessage}")
                Console.err.println(Utils.stackTrace(exception))
              }
            }
          authorizer.close()
        }.getOrElse(client.createAcls(aclBindings, options).all.get)
      }
    }

    def removeAcls(
                    filters: Seq[AclBindingFilter],
                    options: DeleteAclsOptions = new DeleteAclsOptions,
                    forceAuthorizer: Boolean = true): Unit = {
      filters.groupBy(_.patternFilter).foreach { filter =>
        val resource = filter._1
        val entries = filter._2.map(_.entryFilter)
        println(s"Removing ACLs for resource filter '$resource': \n ${entries.map("\t" + _).mkString("\n")}\n")
        val aclBindingFilter = entries.map(entry => new AclBindingFilter(resource, entry)).asJava
        defaultAuthorizer(forceAuthorizer).map { authorizer =>
          authorizer.deleteAcls(None.orNull, aclBindingFilter).asScala
            .map(_.toCompletableFuture.get)
            .foreach { result =>
              result.exception.asScala.foreach { exception =>
                Console.err.println(s"Error while removing ACLs: ${exception.getMessage}")
                Console.err.println(Utils.stackTrace(exception))
              }
            }
          authorizer.close()
        }.getOrElse(client.deleteAcls(aclBindingFilter, options).all.get)
      }
    }

    def getAcls(
                 filter: AclBindingFilter = AclBindingFilter.ANY,
                 options: DescribeAclsOptions = new DescribeAclsOptions,
                 forceAuthorizer: Boolean = true
               ): Seq[AclBinding] = {
      defaultAuthorizer(forceAuthorizer).map { authorizer =>
        val acls = authorizer.acls(filter).asScala.toSeq
        authorizer.close()
        acls
      }.getOrElse(client.describeAcls(filter, options).values.get.asScala.toSeq)
    }

    def listAcls(
                  filter: AclBindingFilter = AclBindingFilter.ANY,
                  principals: Seq[KafkaPrincipal] = Seq.empty,
                  options: DescribeAclsOptions = new DescribeAclsOptions
                ): Unit = {
      getAcls(filter, options)
        .groupBy(_.pattern)
        .foreach { acl =>
          val resource = acl._1
          val entries = acl._2.map(_.entry)
          if (principals.isEmpty) {
            println(s"Current ACLs for resource '$resource': \n ${entries.map("\t" + _).mkString("\n")}\n")
          } else {
            principals.foreach { principal =>
              println(s"ACLs for principal '$principal'")
              println(s"Current ACLs for resource `$resource`: \n ${entries.map("\t" + _).mkString("\n")}\n")
            }
          }
        }
    }
  }

  class KAdminMetrics {

    import org.apache.kafka.common.{Metric, MetricName}
    import org.apache.kafka.tools.{ToolsUtils => JToolsUtils}

    def getMetrics: Map[MetricName, Metric] = {
      getMetrics(".*", ".*")
    }

    def getMetrics(groupRegex: String, nameRegex: String): Map[MetricName, Metric] = {
      client.metrics.asScala
        .filter(metric => metric._1.group.matches(groupRegex) && metric._1.name.matches(nameRegex))
        .toMap
    }

    def listMetrics(): Unit = {
      listMetrics(".*", ".*")
    }

    def listMetrics(groupRegex: String, nameRegex: String): Unit = {
      JToolsUtils.printMetrics(getMetrics(groupRegex, nameRegex).asJava)
    }

  }

}
