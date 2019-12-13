/***********************************************************************
 * Copyright (c) 2013-2019 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.redis.tools.schema

import com.beust.jcommander._
import org.locationtech.geomesa.redis.data.RedisDataStore
import org.locationtech.geomesa.redis.tools.RedisDataStoreCommand
import org.locationtech.geomesa.redis.tools.RedisDataStoreCommand.RedisDataStoreParams
import org.locationtech.geomesa.redis.tools.schema.RedisKeywordsCommand.RedisKeywordsParams
import org.locationtech.geomesa.tools.status.{KeywordsCommand, KeywordsParams}

class RedisKeywordsCommand extends KeywordsCommand[RedisDataStore] with RedisDataStoreCommand {
  override val params = new RedisKeywordsParams
}

object RedisKeywordsCommand {
  @Parameters(commandDescription = "Add/Remove/List keywords on an existing schema")
  class RedisKeywordsParams extends RedisDataStoreParams with KeywordsParams
}
