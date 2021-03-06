package io.findify.s3mock.provider
import java.io.{ObjectInputStream, ObjectOutputStream}
import java.util.UUID

import akka.http.scaladsl.model.DateTime
import better.files.File
import better.files.File.OpenOptions
import com.amazonaws.services.s3.model.ObjectMetadata
import com.typesafe.scalalogging.LazyLogging
import io.findify.s3mock.error.{NoSuchBucketException, NoSuchKeyException}
import io.findify.s3mock.request.{CompleteMultipartUpload, CreateBucketConfiguration}
import io.findify.s3mock.response._

import scala.util.Random

/**
  * Created by shutty on 8/9/16.
  */
class FileProvider(dir:String) extends Provider with LazyLogging {
  val workDir = File(dir)
  if (!workDir.exists) workDir.createDirectories()

  def listBuckets: ListAllMyBuckets = {
    val buckets = File(dir).list.map(f => Bucket(f.name, DateTime(f.lastModifiedTime.toEpochMilli))).toList
    logger.debug(s"listing buckets: ${buckets.map(_.name)}")
    ListAllMyBuckets("root", UUID.randomUUID().toString, buckets)
  }

  def listBucket(bucket: String, prefix: String) = {
    val prefixNoLeadingSlash = prefix.dropWhile(_ == '/')
    val bucketFile = File(s"$dir/$bucket/")
    val bucketFileString = bucketFile.toString
    val bucketFiles = bucketFile.listRecursively.filter(f => {
        val fString = f.toString.drop(bucketFileString.length).dropWhile(_ == '/')
        fString.startsWith(prefixNoLeadingSlash) && !fString.startsWith(".") && !f.isDirectory
      })
    val files = bucketFiles.map(f => {Content(f.toString.drop(bucketFileString.length+1).dropWhile(_ == '/'), DateTime(f.lastModifiedTime.toEpochMilli), "0", f.size, "STANDARD")})
    logger.debug(s"listing bucket contents: ${files.map(_.key)}")
    ListBucket(bucket, prefix, files.toList)
  }

  def createBucket(name:String, bucketConfig:CreateBucketConfiguration) = {
    val bucket = File(s"$dir/$name")
    if (!bucket.exists) bucket.createDirectory()
    logger.debug(s"crating bucket $name")
    CreateBucket(name)
  }
  def putObject(bucket:String, key:String, data:Array[Byte], objectMetadata: ObjectMetadata = null): Unit = {
    createDir(s"$dir/$bucket/$key")
    val file = File(s"$dir/$bucket/$key")
    logger.debug(s"writing file for s3://$bucket/$key to $dir/$bucket/$key, bytes = ${data.length}")
    file.write(data)(OpenOptions.default)

    if(objectMetadata != null) {
      val split = key.split("/").toBuffer
      val metaFileName = split.dropRight(1)
      metaFileName.append(s".${split.last}")

      val metaDataFile = File(s"$dir/$bucket/${metaFileName.mkString}")

      val stream: ObjectOutputStream = new ObjectOutputStream(metaDataFile.newOutputStream(OpenOptions.default))
      stream.writeObject(objectMetadata)
      stream.flush()
      stream.close()
    }
  }
  def getObject(bucket:String, key:String):Array[Byte] = {
    val file = File(s"$dir/$bucket/$key")
    logger.debug(s"reading object for s://$bucket/$key")
    if (!file.exists) throw NoSuchKeyException(bucket, key)
    file.byteArray
  }

  def getMetaData(bucket:String, key:String):ObjectMetadata = {
    val split = key.split("/").toBuffer
    val metaFileName = split.dropRight(1)
    metaFileName.append(s".${split.last}")

    val file = File(s"$dir/$bucket/${metaFileName.mkString}")
    logger.debug(s"reading object for s://$bucket/${metaFileName.mkString}")
    if (!file.exists) null else new ObjectInputStream(file.newInputStream).readObject().asInstanceOf[ObjectMetadata]
  }

  def putObjectMultipartStart(bucket:String, key:String):InitiateMultipartUploadResult = {
    val id = Math.abs(Random.nextLong()).toString
    createDir(s"$dir/.mp/$bucket/$key/$id/.keep")
    logger.debug(s"starting multipart upload for s3://$bucket/$key")
    InitiateMultipartUploadResult(bucket, key, id)
  }
  def putObjectMultipartPart(bucket:String, key:String, partNumber:Int, uploadId:String, data:Array[Byte]) = {
    val file = File(s"$dir/.mp/$bucket/$key/$uploadId/$partNumber")
    logger.debug(s"uploading multipart chunk $partNumber for s3://$bucket/$key")
    file.write(data)(OpenOptions.default)
  }
  def putObjectMultipartComplete(bucket:String, key:String, uploadId:String, request:CompleteMultipartUpload) = {
    val files = request.parts.map(part => File(s"$dir/.mp/$bucket/$key/$uploadId/${part.partNumber}"))
    val parts = files.map(f => f.byteArray)
    createDir(s"$dir/$bucket/$key")
    val file = File(s"$dir/$bucket/$key")
    val data = parts.fold(Array[Byte]())(_ ++ _)
    file.writeBytes(data.toIterator)
    File(s"$dir/.mp/$bucket/$key").delete()
    logger.debug(s"completed multipart upload for s3://$bucket/$key")
    CompleteMultipartUploadResult(bucket, key, "")
  }

  def deleteObject(bucket:String, key:String): Unit = {
    val file = File(s"$dir/$bucket/$key")
    logger.debug(s"deleting object s://$bucket/$key")
    if (!file.exists) throw NoSuchKeyException(bucket, key)
    file.delete()
  }

  def deleteBucket(bucket:String): Unit = {
    val file = File(s"$dir/$bucket")
    logger.debug(s"deleting bucket s://$bucket")
    if (!file.exists) throw NoSuchBucketException(bucket)
    file.delete()
  }

  private def createDir(path:String) = {
    if (!File(path).exists) {
      def create(path:String, dirs:List[String]):Unit = dirs match {
        case Nil => Unit
        case fname :: Nil => Unit
        case head :: tail =>
          val current = File(s"$path/$head")
          if (!current.exists) current.createDirectory()
          create(s"$path/$head", tail)
      }
      create("", path.split("/").toList)
    }
  }

}
