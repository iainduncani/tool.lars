/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package com.ibm.ws.lars.rest;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

public class NonExistentArtefactException extends RepositoryClientException {

    private static final long serialVersionUID = 1L;

    public NonExistentArtefactException() {
        super("Asset not found");
    }

    public NonExistentArtefactException(Exception cause) {
        super(cause);
    }

    public NonExistentArtefactException(String id, String type) {
        super(type + " not found for id: " + id);
    }

    public NonExistentArtefactException(String message) {
        super(message);
    }

    /** {@inheritDoc} */
    @Override
    public Status getResponseStatus() {
        return Response.Status.NOT_FOUND;
    }
}
