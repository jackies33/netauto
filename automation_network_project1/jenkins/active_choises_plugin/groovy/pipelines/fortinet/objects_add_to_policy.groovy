

pipeline {
    agent any

    environment {
        towerServer = 'AWX' // Имя сервера AWX
        towerCredentialsId = '9ac2c3f2-86bf-496b-9ee4-b64a496f4485' // ID учетных данных для подключения к AWX
        jobTemplate_GET_VDOMS = '29' // ID вашего шаблона задания AWX
        jobTemplate_ADD_OBJ = '30' // ID вашего шаблона задания AWX
        jobType = 'run' // Тип работы для выполнения
        exec_devices = "${params.Exec_Devices}" // Значение Exec_Devices передается через environment
        excluded_vdoms = "${params.Excluded_Vdoms}" // Значение Excluded_Vdoms передается через environment
        exec_policy = "${params.Exec_Policy}" // Значение Exec_Policy передается через environment
        needed_objects = "${params.Needed_Objects}"
        type_needed_objects = "${params.Types_Needed_Objects}"
        FortiApiToken = credentials('fortigate_api_token1')
    }

    stages {
        stage('Prepare Devices') {
            steps {
                script {
                    // Функция для преобразования строковых параметров в YAML-формат
                    def convertToYamlList = { param ->
                        if (param instanceof String) {
                            // Преобразуем строку в список, разделяя по запятой
                            return param.split(",").collect { it.trim() }
                        } else if (param instanceof List) {
                            return param
                        }
                        return [param]
                    }

                    def resultsList = []
                    // Преобразуем &#61; в обычное равно
                    def cleanedDevices = exec_devices.replaceAll("&#61;", "=")

                    // Получаем список устройств из exec_devices, переданных в параметре
                    def devices = cleanedDevices.split(',')
                    echo "Devices to process: ${devices}"

                    // Создаем карту с задачами для параллельного выполнения
                    def parallelTasks = [:]

                    // Преобразуем параметры в YAML-списки
                    def excludedVdomsList = convertToYamlList(excluded_vdoms)
                    def neededObjectsList = convertToYamlList(needed_objects)
                    echo "excludedVdomsList: ${excludedVdomsList}"
                    echo "neededObjectsList: ${neededObjectsList}"

                    // Ограничиваем количество параллельных задач для устройств до 20
                    def maxDeviceParallelTasks = 10
                    def taskCount = 0

                    devices.each { device ->
                        if (taskCount >= maxDeviceParallelTasks) {
                            echo "Maximum parallel task limit reached (${maxDeviceParallelTasks}), waiting for tasks to finish."
                            return
                        }

                        // Разделяем строку на host и ip
                        def parts = device.split(':')
                        def host = parts[0]
                        def ip = parts[1]

                        // Проверяем, что и host, и ip найдены
                        if (host && ip) {
                            // Создаем имя для параллельной задачи, используя host и ip
                            def taskName = "job_for_${host}"

                            // Добавляем задачу в параллельные задачи
                            parallelTasks[taskName] = {
                                echo "Starting job for Host: ${host}, IP: ${ip}"

                                // Формируем YAML-подобный формат для передачи в AWX
                                def extraVars = """
                                    fortigate_host: ${ip}
                                    excluded_vdoms: ${excludedVdomsList}
                                    fortigate_access_token: ${FortiApiToken}
                                """
                                echo "Sending to AWX: ${extraVars}"

                                try {
                                    def result = ansibleTower(
                                        jobTemplate: jobTemplate_GET_VDOMS,
                                        jobType: jobType,
                                        towerCredentialsId: towerCredentialsId,
                                        towerServer: towerServer,
                                        extraVars: extraVars,
                                        throwExceptionWhenFail: false
                                    )
                                    echo "AWX response: ${result}"

                                    def vdomsToProcess = result['vdoms_to_process']
                                    if (vdomsToProcess) {
                                        vdomsToProcess = vdomsToProcess.replaceAll("[\\[\\] ]", "")
                                        def vdomsList = vdomsToProcess.split(',')
                                        echo "VDOMs to process for ${host}: ${vdomsList}"

                                        // Разбиваем VDOMs на блоки по 10
                                        def vdomsInBlocks = vdomsList.toList().collate(10)
                                        echo "VDOMs in blocks: ${vdomsInBlocks}"

                                        // Параллельное выполнение для каждого блока VDOMs
                                        vdomsInBlocks.each { vdomBlock ->
                                            def vdomParallelTasks = [:]

                                            vdomBlock.each { vdom ->
                                                def vdomTaskName = "vdom_task_for_${host}_${vdom}"

                                                vdomParallelTasks[vdomTaskName] = {
                                                    echo "Starting VDOM job for ${host}, VDOM: ${vdom}"

                                                    def vdomExtraVars = """
                                                        fortigate_host: ${ip}
                                                        vdom_name: ${vdom}
                                                        fortigate_access_token: ${FortiApiToken}
                                                        policy_name: ${exec_policy}
                                                        needed_objects: ${neededObjectsList}
                                                        type_needed_objects: ${type_needed_objects}
                                                    """
                                                    echo "Sending VDOM job to AWX: ${vdomExtraVars}"

                                                    try {
                                                        def result_main = ansibleTower(
                                                            jobTemplate: jobTemplate_ADD_OBJ,
                                                            jobType: jobType,
                                                            towerCredentialsId: towerCredentialsId,
                                                            towerServer: towerServer,
                                                            extraVars: vdomExtraVars,
                                                            throwExceptionWhenFail: false
                                                        )
                                                        //echo "AWX VDOM response: ${result_main}"
                                                        def MyResult_task_status = result_main['execution_status'].toString()
                                                        def MyResult_error_details = result_main['error_message'].toString()
                                                        if (MyResult_task_status) {
                                                            echo "${host}(${vdom}) - ${MyResult_task_status} ${MyResult_error_details}"
                                                            resultsList << "${host}(${vdom}) - ${MyResult_task_status} ${MyResult_error_details}"
                                                        } else {
                                                            echo "${host}(${vdom}) - No result"
                                                            resultsList << "${host}(${vdom})  -   No result"
                                                        }
                                                    } catch (e) {
                                                        echo "Error processing VDOM job for ${host}, VDOM: ${vdom}, Error: ${e}"
                                                    }
                                                }
                                            }

                                            // Запускаем параллельные задачи для каждого блока VDOM
                                            if (vdomParallelTasks.size() > 0) {
                                                echo "Running VDOM parallel tasks for ${host}"
                                                parallel vdomParallelTasks
                                            }
                                        }
                                    }
                                } catch (e) {
                                    echo "Error processing job for ${host}, IP: ${ip}, Error: ${e}"
                                }
                            }
                            taskCount++
                        } else {
                            echo "No host or IP found for device: ${device}"
                        }
                    }

                    // Запускаем все параллельные задачи для хостов
                    if (parallelTasks.size() > 0) {
                        echo "Running parallel tasks for devices"
                        parallel parallelTasks
                    }

                    echo "Results List:"
                    resultsList.each { result ->
                        echo result
                    }
                }
            }
        }

        stage('Output') {
            steps {
                script {
                    echo "Pipeline completed"
                }
            }
        }
    }

    post {
        always {
            echo 'Завершение работы'
        }
    }
}
