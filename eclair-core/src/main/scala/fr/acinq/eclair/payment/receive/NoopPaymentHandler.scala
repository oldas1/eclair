/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment.receive

import akka.actor.{Actor, ActorLogging, ActorRef}

/**
  * Created by PM on 16/06/2016.
  */
class NoopPaymentHandler extends Actor with ActorLogging {

  override def receive: Receive = forward(context.system.deadLetters)

  def forward(handler: ActorRef): Receive = {
    case newHandler: ActorRef =>
      log.info(s"registering actor $handler as payment handler")
      context become forward(newHandler)
    case msg => handler forward msg
  }

}