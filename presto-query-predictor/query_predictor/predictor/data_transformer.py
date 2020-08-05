# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
This module contains the component to preprocess and transform the Presto log dataset.
"""
import logging
from logging.config import fileConfig
from typing import Callable
from typing import Iterator
from typing import List
from typing import Tuple
from typing import Union

import pandas as pd

from .constant import CPU_TIME_LABEL
from .constant import PEAK_MEMORY_LABEL
from .constant import QUERY_COLUMN
from .constant import QUERY_STATE_COLUMN
from .exceptions import DataTransformerException
from .label_creator import CPUTimeLabelCreator
from .label_creator import PeakMemoryLabelCreator

fileConfig("../conf/logging.conf")
_logger = logging.getLogger(__name__)


class DataTransformer:
    """
    The class to transform presto data frames with various preprocessing techniques.

    It allows to execute both predefined functions (e.g. to lowercase) and user-
    customized methods on the data frame passed in.

    :param data_frame: The target data frame for transformations.
    :param func_strs: A sequence of strings representing predefined functions for
    transformations.
    :param funcs: A sequence of extra customized functions.

    To use:
    >>> transformer_list = [
    >>>   "drop_failed_queries",
    >>>   "to_lower_queries",
    >>>   "create_labels"
    >>> ]
    >>> data_transformer = DataTransformer(data_frame, transformer_list)
    >>> data_transformer.transform()
    """

    def __init__(
        self,
        data_frame,
        func_strs: Union[Tuple[str], List[str]] = (),
        funcs: Iterator[Callable[[pd.DataFrame], pd.DataFrame]] = (),
    ) -> None:
        self.data_frame = data_frame
        self.func_strs = func_strs
        self.funcs = funcs

    def transform(self) -> pd.DataFrame:
        """
        The main function to transform a data frame. It executes both predefined
        and customized methods.

        :return: A pandas data frame with transformed data.
        :raise DataTransformerException: If a predefined transformer cannot be found
        for a function string.
        """
        for func_str in self.func_strs:
            _logger.info("Executing %s on the data frame...", func_str)
            transformer = getattr(self, func_str, None)
            if transformer:
                transformer()
            else:
                raise DataTransformerException(
                    f"{func_str} is not an available transformer"
                )

        for func in self.funcs:
            self.data_frame = func(self.data_frame)

        _logger.debug(
            "The dataset's shape is %s after the transformations",
            self.data_frame.shape,
        )

        return self.data_frame

    def create_labels(self) -> pd.DataFrame:
        """
        Creates labeled columns.

        :return: A pandas data frame with transformed data.
        """
        self.create_cpu_time_label()
        self.create_peak_memory_label()

        return self.data_frame

    def create_cpu_time_label(self) -> pd.DataFrame:
        """
        Creates a labeled column with CPU time in categories.

        :return: A pandas data frame with a labeled CPU time column.
        """
        _logger.info("Creating the cpu time labels...")
        self.data_frame[CPU_TIME_LABEL] = self.data_frame.apply(
            CPUTimeLabelCreator().label, axis=1
        )
        return self.data_frame

    def create_peak_memory_label(self) -> pd.DataFrame:
        """
        Creates a labeled column with peak memory in categories.

        :return: A pandas data frame with a labeled peak memory column.
        """
        _logger.info("Creating the peak memory labels...")
        self.data_frame[PEAK_MEMORY_LABEL] = self.data_frame.apply(
            PeakMemoryLabelCreator().label, axis=1
        )
        return self.data_frame

    def drop_failed_queries(self) -> pd.DataFrame:
        """
        Drops failed presto queries' logs.

        :return: A pandas data frame with successful presto queries.
        """
        if QUERY_STATE_COLUMN in self.data_frame.columns:
            self.data_frame = self.data_frame[
                self.data_frame[QUERY_STATE_COLUMN] == "FINISHED"
            ]
        return self.data_frame

    def to_lower_queries(self) -> pd.DataFrame:
        """
        Converts each sql statement to lowercase.

        :return: A pandas data frame with all sql statements in lowercase strings.
        """
        self.data_frame[QUERY_COLUMN] = self.data_frame[
            QUERY_COLUMN
        ].str.lower()
        return self.data_frame

    def select_training_columns(self) -> pd.DataFrame:
        """
        Selects the columns for model training. The columns contain the sql statements,
        labeled CPU time, and labeled peak memory.

        :return: A pandas data frame with minimal number of columns for training.
        """
        self.data_frame = self.data_frame[
            QUERY_COLUMN, CPU_TIME_LABEL, PEAK_MEMORY_LABEL
        ]
        return self.data_frame
