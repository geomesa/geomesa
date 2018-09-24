/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.fs

import java.util.concurrent.ConcurrentHashMap

import com.github.benmanes.caffeine.cache.{CacheLoader, Caffeine}
import com.typesafe.scalalogging.LazyLogging
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileContext, Path}
import org.locationtech.geomesa.fs.storage.api.FileSystemStorage
import org.locationtech.geomesa.fs.storage.common.FileSystemStorageFactory
import org.locationtech.geomesa.fs.storage.common.utils.PathCache
import org.locationtech.geomesa.utils.stats.MethodProfiling

/**
  * Manages the storages and associated simple feature types underneath a given path
  *
  * @param fc file context
  * @param conf configuration
  * @param root root path for the data store
  */
class FileSystemStorageManager private (fc: FileContext, conf: Configuration, root: Path)
    extends MethodProfiling with LazyLogging {

  import scala.collection.JavaConverters._

  private val cache = new ConcurrentHashMap[String, (Path, FileSystemStorage)]().asScala

  /**
    * Gets the storage associated with the given simple feature type, if any
    *
    * @param typeName simple feature type name
    * @return
    */
  def storage(typeName: String): Option[FileSystemStorage] = {
    cache.get(typeName).map(_._2) // check cached values
        .orElse(Some(defaultPath(typeName)).filter(PathCache.exists(fc, _)).flatMap(loadPath)) // check expected (default) path
        .orElse(loadAll().find(_.getMetadata.getSchema.getTypeName == typeName)) // check other paths until we find it
  }

  /**
    * Gets the storage under a given path, if any
    *
    * @param path root path for the storage
    * @return
    */
  def storage(path: Path): Option[FileSystemStorage] =
    cache.collectFirst { case (_, (p, storage)) if p == path => storage }.orElse(loadPath(path))

  /**
    * Gets all storages under the root path
    *
    * @return
    */
  def storages(): Seq[FileSystemStorage] = {
    loadAll().foreach(_ => Unit) // force loading of everything
    cache.map { case (_, (_, storage)) => storage }.toSeq
  }

  /**
    * Caches a storage instance for future use. Avoids loading it a second time if referenced later.
    *
    * @param path path for the storage
    * @param storage storage instance
    */
  def register(path: Path, storage: FileSystemStorage): Unit =
    cache.put(storage.getMetadata.getSchema.getTypeName, (path, storage))

  /**
    * Default path for a given simple feature type name. Generally the simple feature type will go under
    * a folder with the type name, but this is not required
    *
    * @param typeName simple feature type name
    * @return
    */
  def defaultPath(typeName: String): Path = new Path(root, typeName)

  /**
    * Loads all storages under this root (if they aren't already loaded)
    *
    * @return
    */
  private def loadAll(): Iterator[FileSystemStorage] = {
    if (!PathCache.exists(fc, root)) { Iterator.empty } else {
      val dirs = PathCache.list(fc, root).filter(_.isDirectory).map(_.getPath)
      dirs.filterNot(path => cache.exists { case (_, (p, _)) => p == path }).flatMap(loadPath)
    }
  }

  /**
    * Attempt to load a storage under the given root path. Requires an appropriate storage implementation
    * to be available on the classpath.
    *
    * @param path storage root path
    * @return
    */
  private def loadPath(path: Path): Option[FileSystemStorage] = {
    import org.locationtech.geomesa.utils.conversions.JavaConverters._

    def complete(storage: Option[FileSystemStorage], time: Long): Unit =
      logger.debug(s"${ if (storage.isDefined) "Loaded" else "No" } storage at path '$path' in ${time}ms")

    profile(complete _) {
      val loaded = FileSystemStorageFactory.factories().flatMap(_.load(fc, conf, path).asScala.iterator)
      if (!loaded.hasNext) { None } else {
        val storage = loaded.next
        register(path, storage)
        Some(storage)
      }
    }
  }
}

object FileSystemStorageManager {

  private val cache = Caffeine.newBuilder().build(
    new CacheLoader[(FileContext, Configuration, Path), FileSystemStorageManager]() {
      override def load(key: (FileContext, Configuration, Path)): FileSystemStorageManager =
        new FileSystemStorageManager(key._1, key._2, key._3)
    }
  )

  /**
    * Load a cached storage manager instance
    *
    * @param fc file context
    * @param conf configuration
    * @param root data store root path
    * @return
    */
  def apply(fc: FileContext, conf: Configuration, root: Path): FileSystemStorageManager = cache.get((fc, conf, root))
}
