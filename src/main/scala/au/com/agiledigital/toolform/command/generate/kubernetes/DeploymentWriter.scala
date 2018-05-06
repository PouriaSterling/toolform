package au.com.agiledigital.toolform.command.generate.kubernetes

import au.com.agiledigital.toolform.model.ToolFormService
import au.com.agiledigital.toolform.model.{Project, Resource, Volume}
import scala.util.{Failure, Success, Try}
// import scala.collection._
import collection.JavaConversions._
import cats.implicits._

object DeploymentWriter extends KubernetesWriter {

  private def writeTemplateMetadata(service: ToolFormService): Result[Unit] = {
    val selectorEntry = determineSelectorEntry(service)
    for {
      _ <- write("metadata:")
      _ <- indented {
            for {
              _ <- write("labels:")
              _ <- indented {
                    for {
                      _ <- write(selectorEntry)
                    } yield ()
                  }
            } yield ()
          }
    } yield ()
  }

  private def writeEnvironmentVariable(entry: (String, String)): Result[Unit] = {
    val (name, value) = entry
    for {
      _ <- write("-")
      _ <- indented {
            for {
              _ <- write(s"name: $name")
              _ <- write(s"value: $value")
            } yield ()
          }
    } yield ()
  }

  private def writeEnvironmentVariables(service: ToolFormService): Result[Unit] =
    if (service.environment.nonEmpty) {
      for {
        _ <- write("env:")
        _ <- indented {
              for {
                _ <- service.environment.toList
                      .traverse_((entry) => writeEnvironmentVariable(entry))
              } yield ()
            }
      } yield ()
    } else {
      identity
    }

  private def writeContainerPorts(containerPorts: List[Int]): Result[Unit] =
    if (containerPorts.nonEmpty) {
      for {
        _ <- write("ports:")
        _ <- indented {
              for {
                _ <- containerPorts.traverse_(containerPort => write(s"- containerPort: $containerPort"))
              } yield ()
            }
      } yield ()
    } else {
      identity
    }

  // TESTING
  private def writeVolumeMounts(service: ToolFormService, project: Project): Result[Unit] = {
    val componentID = service.id
    Left("There was an error")

    // look through project topology and find volume claims for current component/resource
    val relatedVolumeIDs: Seq[String] = project.topology.volumes
      .getOrElse(Nil)
      .filter(volume => volume.to.refId == componentID)
      .map(_.from.refId)

    val volumesWithPaths: Option[Seq[(String, String)]] = Some(relatedVolumeIDs flatMap { volumeId =>
      findPathsFromVolumeID(project, volumeId).map((volumeId, _))
    }).filter(_.nonEmpty)

    val maybeVolumeMounts: Option[Result[Unit]] = volumesWithPaths map { list =>
      list.toList traverse_ {
        case (volumeId, path) =>
          val result: Result[Unit] = for {
            _ <- write(s"- name: ${volumeId}")
            _ <- write(s"  mountPath: ${path}")
          } yield ()
          result
      }
    }

    maybeVolumeMounts match {
      case Some(volumeMounts) =>
        for {
          _ <- write("volumeMounts:")
          _ <- indented(volumeMounts)
        } yield ()
      case None => identity
    }
  }

  private def findPathsFromVolumeID(project: Project, resourceID: String): List[String] =
    project.resources.get(resourceID) match {
      case Some(resource) => {
        // returns a List(String)
        returnVolumePath(resource, resourceID)
      }
      case None =>
        println(s"Warning: '$resourceID' does not exist")
        Nil
    }

  private def returnVolumePath(resource: Resource, id: String): List[String] = {
    val paths = resource.settings match {
      case Some(settings) => {
        Try(settings.getStringList("paths")) match {
          case Success(list) => list
          // no paths specified in project.conf
          case Failure(error) => {
            println(s"Error: no path specified for resource: $id.")
            val res: java.util.List[String] = List("error")
            res
          }
        }
      }
      case None => {
        println(s"Warning: no path specified for resource: $id.")
        val res: java.util.List[String] = List("error")
        res
      }
    }
    String.join("-", paths).split("-").toList
  }

  private def writeContainer(project: Project, service: ToolFormService): Result[Unit] = {
    val imageName   = determineImageName(project.id, service)
    val serviceName = determineServiceName(service)
    val containerPorts = (service.externalPorts ++ service.exposedPorts)
      .map((portMapping) => portMapping.targetPort)

    for {
      _ <- write("containers:")
      _ <- write("-")
      _ <- indented {
            for {
              _ <- write(s"image: $imageName")
              _ <- write(s"name: $serviceName")
              _ <- write("imagePullPolicy: IfNotPresent") // Makes locally built images work
              _ <- writeEnvironmentVariables(service)
              _ <- writeContainerPorts(containerPorts)
              _ <- writeVolumeMounts(service, project)
            } yield ()
          }
    } yield ()
  }

  /**
    * Writes a Kubernetes "deployment" specification based on the provided toolform service.
    *
    * A deployment is an abstraction to control the creation of pods.
    * It defines what image to pull and how many replicas to create.
    *
    *
    * @see https://kubernetes.io/docs/concepts/workloads/controllers/deployment/
    *
    * @param project    the project object which is used to get deployment information,
    * @param service    the service object that will be used to create the deployment spec.
    * @return           a state monad encapsulating the context of the writing process after the method has completed.
    */
  def writeDeployment(project: Project, service: ToolFormService): Result[Unit] = {
    val serviceName = determineServiceName(service)

    for {
      _ <- write("---")
      _ <- write("apiVersion: extensions/v1beta1")
      _ <- write("kind: Deployment")
      _ <- write("metadata:")
      _ <- indented {
            for {
              _ <- writeAnnotations(service)
              _ <- write(s"name: $serviceName")
            } yield ()
          }
      _ <- write("spec:")
      _ <- indented {
            for {
              _ <- write(s"replicas: 1")
              _ <- write(s"template:")
              _ <- indented {
                    for {
                      _ <- writeTemplateMetadata(service)
                      _ <- write(s"spec:")
                      _ <- indented {
                            for {
                              _ <- writeContainer(project, service)
                            } yield ()
                          }
                    } yield ()
                  }
            } yield ()
          }
    } yield ()
  }
}
