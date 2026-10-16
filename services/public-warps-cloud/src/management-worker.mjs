import { ManagementApplication } from './management-application.mjs';

export default {
  fetch(request, env) {
    return new ManagementApplication(env).handle(request);
  },
};
