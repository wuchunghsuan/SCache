#!/usr/bin/env python

import numpy as np
import os
import pandas as pd


trace_path = '/home/frankfzw/SCache/sim/2012-10/attempt.csv'
res_path = '/home/frankfzw/SCache/sim/res'
schedule = ['fifo', 'round_robin', 'ideal', 'scache']
# schedule = ['scache', 'fifo']
hosts_num = 10

def deal_na_int(x):
	if (x == '' or x == None):
		return -1
	else:
		return int(x)


field_names = {'jtid': int, 'jobid': int, 'tasktype': str, 'taskid': int, 'attempt': int, 'startTime': int, 'shuffleTime': int, 'sortTime': int, 'finishTime': int, 'status': int, 'rack': str, 'hostname': str}
converters = {'shuffleTime': deal_na_int, 'sortTime': deal_na_int}
raw_talbe = pd.read_csv(filepath_or_buffer=trace_path, dtype=field_names, converters=converters)
reduce_talbe = raw_talbe.loc[raw_talbe['tasktype'] == 'r']

def find_min(times):
	tag = times[0]
	index = 0
	for i in range(len(times)):
		if (times[i] < tag):
			tag = times[i]
			index = i
	return index


def round_robin_schedule(reduce_tasks, num_hosts):
	times = np.zeros(num_hosts)
	tasks_size = len(reduce_tasks.index)
	finish_time = np.array(list(reduce_tasks['finishTime'].values))
	shuffle_time = np.array(list(reduce_tasks['shuffleTime'].values))
	sort_time = np.array(list(reduce_tasks['sortTime'].values))
	start_time = np.array(list(reduce_tasks['startTime'].values))
	shuffle_time = sort_time - shuffle_time
	run_time = finish_time - start_time - shuffle_time
	for i in range(tasks_size):
		# print '{}\t{}'.format(r['startTime'], r['finishTime'])
		times[i % num_hosts] += run_time[i]
	# print sum(times)
	# print times
	return np.amax(times)

def fifo_schedule(reduce_tasks, num_hosts):
	times = np.zeros(num_hosts)
	tasks_size = len(reduce_tasks.index)
	finish_time = np.array(list(reduce_tasks['finishTime'].values))
	start_time = np.array(list(reduce_tasks['startTime'].values))
	run_time = finish_time - start_time
	if tasks_size < num_hosts:
		for i in range(tasks_size):
			times[i] += run_time[i]
	else:
		for i in range(tasks_size):
			index = find_min(times)
			times[index] += run_time[i]
	return np.max(times)

def scache_schedule(reduce_tasks, num_hosts):
	times = np.zeros(num_hosts)
	tasks_size = len(reduce_tasks.index)
	finish_time = np.array(list(reduce_tasks['finishTime'].values))
	start_time = np.array(list(reduce_tasks['startTime'].values))
	shuffle_time = np.array(list(reduce_tasks['shuffleTime'].values))
	sort_time = np.array(list(reduce_tasks['sortTime'].values))
	shuffle_time = sort_time - shuffle_time
	run_time = finish_time - start_time - shuffle_time
	schedule_turns = tasks_size / num_hosts + 1
	if tasks_size < num_hosts:
		for i in range(tasks_size):
			times[i] += run_time[i]
	else:
		for i in range(num_hosts):
			times[i] += run_time[i]
		for i in range(num_hosts, tasks_size):
			index = find_min(times)
			if shuffle_time[i] > times[index]:
				times[index] += (shuffle_time[i] - times[index])
			times[index] += run_time[i]
	return np.max(times)


def ideal_schedule(reduce_tasks, num_hosts):
	times = np.zeros(num_hosts)
	tasks_size = len(reduce_tasks.index)
	finish_time = np.array(list(reduce_tasks['finishTime'].values))
	shuffle_time = np.array(list(reduce_tasks['shuffleTime'].values))
	sort_time = np.array(list(reduce_tasks['sortTime'].values))
	start_time = np.array(list(reduce_tasks['startTime'].values))
	shuffle_time = sort_time - shuffle_time
	run_time = finish_time - start_time - shuffle_time
	if tasks_size < num_hosts:
		for i in range(tasks_size):
			times[i] += run_time[i]
	else:
		target_time = sum(run_time) / num_hosts
		return target_time
		run_time = np.sort(run_time)
		tid = tasks_size - 1
		while tid >= 0:
			for i in range(num_hosts):
				if (times[i] > target_time) or (tid < 0):
					break
				times[i] += run_time[tid]
				tid -= 1
			times = np.sort(times)
	# print run_time
	# print sum(times)
	# print times
	return np.max(times)


def do_schedule(reduce_tasks, num_hosts, scheme):
	if (scheme == 'fifo'):
		return fifo_schedule(reduce_tasks, num_hosts)
	elif (scheme == 'round_robin'):
		return round_robin_schedule(reduce_tasks, num_hosts)
	elif (scheme == 'ideal'):
		return ideal_schedule(reduce_tasks, num_hosts)
	elif (scheme == 'scache'):
		return scache_schedule(reduce_tasks, num_hosts)
	else:
		print 'Wrong scheme %s' % scheme
		return None

def main():
	print 'Start simulation'
	print 'Trace: %s' % trace_path
	print 'Scheduler: %s' % schedule

	jobids = set(reduce_talbe['jobid'].values)
	jobids_list = list(jobids)
	# delete empty ones
	for jid in jobids_list:
		reduce_tasks = reduce_talbe.loc[(reduce_talbe['jobid'] == jid) & (reduce_talbe['status'] == 0)]
		if (len(reduce_tasks.index) == 0 or len(reduce_tasks.index) <= hosts_num):
			jobids.remove(jid)

	jobids = np.array(list(jobids))

	for scheme in schedule:
		print 'Processing %s' % scheme
		res = np.zeros(len(jobids))
		for i in range(len(jobids)):
			# if jobids[i] != 4817:
			# 	continue
			reduce_tasks = reduce_talbe.loc[(reduce_talbe['jobid'] == jobids[i]) & (reduce_talbe['status'] == 0)]
			t = do_schedule(reduce_tasks, hosts_num, scheme)
			if t is None:
				break
			res[i] = t
		df = pd.DataFrame({'jid':jobids, 'time':res})
		df.to_csv('{}/{}.csv'.format(res_path, scheme))




if __name__ == '__main__':
	main()