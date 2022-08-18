#!/bin/bash -e

#
# /*
#  * Copyright (c) 2022, salesforce.com, inc.
#  * All rights reserved.
#  * Licensed under the BSD 3-Clause license.
#  * For full license text, see LICENSE.txt file in the repo root or
#  * https://opensource.org/licenses/BSD-3-Clause
#  */
#

main_class=$1

java -cp "/opt/jw/*" "${main_class}"
